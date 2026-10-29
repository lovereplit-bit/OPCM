#!/usr/bin/env bash
# Resolves which OpenCode release to bundle into an app build.
#
# Usage: runtime/resolve-upstream.sh [tag]     (no tag: upstream.json's pin, else the newest vN.x.y tag of the pinned major line)
# Outputs tag, version and commit to $GITHUB_OUTPUT (or stdout).
# Environment: UPSTREAM_REPO, PINNED_MAJOR, PINNED_TAG (optional exact version to hold the interface steady),
#              UPSTREAM_GIT_URL (default https://github.com/$UPSTREAM_REPO.git).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=versions.env
. "$here/versions.env"

up="$UPSTREAM_REPO"
git_url="${UPSTREAM_GIT_URL:-https://github.com/$up.git}"
out="${GITHUB_OUTPUT:-/dev/stdout}"
tag="${1:-}"

die() { echo "resolve-upstream: $*" >&2; exit 1; }

if [ -z "$tag" ]; then
  # An exact pin (upstream.json: "pin") holds the bundled server - and with it the interface people know -
  # steady across builds. Without it the newest tag of the pinned major line is taken.
  tag="${PINNED_TAG:-}"
fi

if [ -z "$tag" ]; then
  # GitHub's "latest release" can be an older line (upstream keeps publishing 1.x next to 2.x): take the newest plain tag
  # of the major line this app is built for.
  major="${PINNED_MAJOR:?set PINNED_MAJOR or pass a tag}"
  tag="$(git ls-remote --tags "$git_url" "refs/tags/v$major.*" | awk '{print $2}' | grep -v '\^{}$' | sed 's#refs/tags/##' \
    | grep -E "^v$major\.[0-9]+\.[0-9]+$" | sort -V | tail -1 || true)"
  [ -n "$tag" ] || die "no v$major.x tag found in $up"
fi

[[ "$tag" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?$ ]] || die "'$tag' is not a version tag"

refs="$(git ls-remote "$git_url" "refs/tags/$tag" "refs/tags/$tag^{}")"
peeled="$(awk -v t="refs/tags/$tag^{}" '$2 == t {print $1}' <<<"$refs")"
direct="$(awk -v t="refs/tags/$tag" '$2 == t {print $1}' <<<"$refs")"
commit="${peeled:-$direct}"

[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || die "could not resolve $tag to a commit"

printf 'tag=%s\nversion=%s\ncommit=%s\n' "$tag" "${tag#v}" "$commit" >>"$out"
echo "$up $tag -> $commit" >&2
