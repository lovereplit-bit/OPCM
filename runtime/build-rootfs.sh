#!/usr/bin/env bash
# Builds the Linux userland archive that the app unpacks on first start.
#
# Usage: runtime/build-rootfs.sh <opencode-binary> <out-dir> <stamp-prefix>
#   writes <out-dir>/rootfs.tgz and <out-dir>/rootfs.stamp (the stamp tells the app when to unpack again)
# Needs Docker on an arm64 host.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=versions.env
. "$here/versions.env"

bin="$(realpath "${1:?usage: build-rootfs.sh <opencode-binary> <out-dir> <stamp-prefix>}")"
mkdir -p "${2:?usage: build-rootfs.sh <opencode-binary> <out-dir> <stamp-prefix>}"
out="$(cd "$2" && pwd)"
prefix="${3:?usage: build-rootfs.sh <opencode-binary> <out-dir> <stamp-prefix>}"

if [ "$(uname -m)" != "aarch64" ]; then
  echo "The userland has to be built on an arm64 machine (it installs and runs arm64 programs). Found: $(uname -m)" >&2
  echo "Continuing anyway"
fi

ctx="$(mktemp -d)"
cid=""
cleanup() {
  [ -n "$cid" ] && docker rm -f "$cid" >/dev/null 2>&1 || true
  rm -rf "$ctx"
}
trap cleanup EXIT

cp "$bin" "$ctx/opencode"
cp -a "$here/rootfs-overlay" "$ctx/overlay"
cp "$here/Dockerfile" "$ctx/Dockerfile"

tag="opencode-android-rootfs:build"
docker build --platform linux/arm64 --pull \
  --build-arg "UBUNTU_TAG=$UBUNTU_TAG" --build-arg "NODE_LINE=$NODE_LINE" \
  -t "$tag" "$ctx"

# Smoke test inside the image before it is archived.
docker run --rm --platform linux/arm64 "$tag" sh -c 'opencode --version && node --version && python3 --version && git --version && rg --version | head -1 && uv --version && test -x /usr/local/bin/opencode'

cid="$(docker create --platform linux/arm64 "$tag" /bin/true)"
docker export "$cid" | gzip -6 >"$out/rootfs.tgz"

sha="$(sha256sum "$out/rootfs.tgz" | cut -c1-12)"
printf '%s-%s' "$prefix" "$sha" >"$out/rootfs.stamp"

ls -l "$out"
echo "stamp: $(cat "$out/rootfs.stamp")"
