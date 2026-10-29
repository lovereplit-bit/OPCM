#!/usr/bin/env bash
# Starts a freshly built OpenCode server the way the Android app starts it and checks every interface the app depends
# on. A build that fails any required check must never be offered to phones.
#
# Usage: runtime/contract-test.sh <opencode-binary> <expected-version>
set -uo pipefail

bin="$(realpath "${1:?usage: contract-test.sh <opencode-binary> <expected-version>}")"
want="${2:?usage: contract-test.sh <opencode-binary> <expected-version>}"
work="$(mktemp -d)"
pid=""
failed=0

cleanup() {
  [ -n "$pid" ] && kill "$pid" 2>/dev/null
  wait 2>/dev/null
  rm -rf "$work"
}
trap cleanup EXIT

pass() { echo "ok    $1"; }
fail() { echo "FAIL  $1"; failed=$((failed + 1)); }
warn() { echo "warn  $1"; }
check() { # check <name> <command...>
  local name="$1"
  shift
  if "$@"; then pass "$name"; else fail "$name"; fi
}

mkdir -p "$work/home" "$work/guest"
printf '# test instructions\n' >"$work/guest/AGENTS.md"

port="$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
password="contract-$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
base="http://127.0.0.1:$port"
auth="opencode:$password"

check "version flag" bash -c "'$bin' --version | grep -qF '$want'"

# The same variables the app sets (see ProotCommand.guestEnv), with a scratch HOME and no XDG overrides, so the data
# location is the one the app backs up before an update.
env -i PATH="$PATH" HOME="$work/home" LANG=C.UTF-8 TMPDIR="$work" \
  OPENCODE_PASSWORD="$password" OPENCODE_CLIENT=android OPENCODE_DISABLE_AUTOUPDATE=1 \
  OPENCODE_CONFIG_CONTENT="{\"instructions\":[\"$work/guest/AGENTS.md\"]}" \
  "$bin" serve --hostname 127.0.0.1 --port "$port" >"$work/server.log" 2>&1 &
pid=$!

up=0
for _ in $(seq 1 120); do
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 -u "$auth" "$base/api/info" || true)"
  [ "$code" = "200" ] && { up=1; break; }
  kill -0 "$pid" 2>/dev/null || break
  sleep 1
done

if [ "$up" != 1 ]; then
  fail "server answers on /api/info"
  echo "---- server log ----"
  tail -n 60 "$work/server.log"
  exit 1
fi

pass "server answers on /api/info"

curl -s --max-time 5 -u "$auth" "$base/api/info" >"$work/info.json"
quiet() { "$@" >/dev/null; }

check "info reports the version" quiet jq -e --arg v "$want" '.version | contains($v)' "$work/info.json"
check "info has urls and paths" quiet jq -e '(.urls | type == "array") and (.paths.tmp | type == "string")' "$work/info.json"

status() { curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$@"; }

check "no credentials is refused" test "$(status "$base/api/info")" = 401
check "wrong password is refused" test "$(status -u 'opencode:nope' "$base/api/info")" = 401

# Pairing: how the app signs its web view in.
curl -s --max-time 5 -X POST -u "$auth" "$base/api/pair" >"$work/pair.json"
code="$(jq -r '.code // empty' "$work/pair.json")"
check "pairing code is issued" test -n "$code"

curl -s -D "$work/connect.headers" -o /dev/null --max-time 5 -H 'Accept: text/html' "$base/auth/connect/$code"
check "pairing link redirects" bash -c "head -1 '$work/connect.headers' | grep -q ' 302'"
cookie="$(tr -d '\r' <"$work/connect.headers" | grep -i '^set-cookie:' | head -1 | sed 's/^[Ss]et-[Cc]ookie: *//; s/;.*//')"
check "cookie is named for the port" test "${cookie%%=*}" = "opencode_session_$port"
check "cookie is HttpOnly" grep -qi '^set-cookie:.*httponly' "$work/connect.headers"
check "the cookie authenticates" test "$(status -H "Cookie: $cookie" "$base/api/info")" = 200
check "a pairing code works once" test "$(status -H 'Accept: text/html' "$base/auth/connect/$code")" = 401

# The web interface is served from inside the executable.
curl -s -D "$work/page.headers" -o "$work/page.html" --max-time 10 -H "Cookie: $cookie" -H 'Accept: text/html' "$base/"
check "web interface is served" bash -c "head -1 '$work/page.headers' | grep -q ' 200' && grep -qi '^content-type: *text/html' '$work/page.headers'"
check "web interface looks like a page" grep -qi '<html' "$work/page.html"

# Events: the app watches them to post notifications while it is in the background.
curl -s -N -D "$work/sse.headers" -o /dev/null --max-time 4 -u "$auth" "$base/api/event" || true
check "event stream opens" bash -c "head -1 '$work/sse.headers' | grep -q ' 200' && grep -qi '^content-type: *text/event-stream' '$work/sse.headers'"

# Where the app looks for data to back up before an update.
check "data directory is where the app expects" test -d "$work/home/.local/share/opencode"
if ls "$work/home/.local/share/opencode"/*.db >/dev/null 2>&1; then pass "database file is created"; else warn "no .db file yet (created on first use?)"; fi

check "server is still running" kill -0 "$pid"

if [ "$failed" -ne 0 ]; then
  echo "$failed check(s) failed"
  echo "---- server log ----"
  tail -n 60 "$work/server.log"
  exit 1
fi

echo "contract test passed"
