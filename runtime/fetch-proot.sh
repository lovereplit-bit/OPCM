#!/usr/bin/env bash
# Downloads proot and its loader from the Termux package repository and turns them into the three files the app
# executes from its native library directory:
#
#   libproot.so          the launcher
#   libproot-loader.so   its helper, started inside the guest
#   libtalloc.so         the talloc library proot needs
#   libandroid-shmem.so  the shared memory helper library proot needs (if required)
#
# Android only unpacks files named lib*.so into that directory, so the names are rewritten with patchelf. The result is
# refused unless every library it needs is something Android itself provides.
#
# Usage: runtime/fetch-proot.sh <dest-dir>      (needs curl, dpkg-deb, patchelf, readelf, python3, xz)
# Environment: TERMUX_MIRRORS (space separated, see versions.env). EXPECT_MACHINE exists for the offline test only.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=versions.env
. "$here/versions.env"

dest="${1:?usage: fetch-proot.sh <dest-dir>}"
mkdir -p "$dest"
dest="$(cd "$dest" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Libraries every Android device provides. Anything else in a NEEDED entry means the package layout changed.
system_libs="libc.so libm.so libdl.so liblog.so"

fetch_index() {
  local mirror
  for mirror in $TERMUX_MIRRORS; do
    for name in Packages.xz Packages.gz Packages; do
      if curl -fsSL --retry 3 --max-time 120 -o "$work/index.$name" "$mirror/dists/stable/main/binary-aarch64/$name"; then
        case "$name" in
          Packages.xz) xz -dc "$work/index.$name" >"$work/Packages" ;;
          Packages.gz) gzip -dc "$work/index.$name" >"$work/Packages" ;;
          *) cp "$work/index.$name" "$work/Packages" ;;
        esac
        repo="$mirror"
        return 0
      fi
    done
    echo "mirror $mirror did not answer, trying the next one" >&2
  done
  return 1
}

repo=""
fetch_index || { echo "None of the Termux mirrors could be reached" >&2; exit 1; }
echo "Using $repo"

download() { # download <package> <extract-dir>
  local pkg="$1" dir="$2" version file sha
  mapfile -t info < <(python3 "$here/termux_index.py" "$work/Packages" "$pkg")
  version="${info[0]}"
  file="${info[1]}"
  sha="${info[2]}"

  curl -fsSL --retry 3 --max-time 300 -o "$work/$pkg.deb" "$repo/$file"
  echo "$sha  $work/$pkg.deb" | sha256sum -c - >/dev/null || { echo "Checksum mismatch for $pkg" >&2; exit 1; }
  echo "$pkg $version ($sha)"
  mkdir -p "$dir"
  dpkg-deb -x "$work/$pkg.deb" "$dir"
}

download proot "$work/proot"
download libtalloc "$work/talloc"

prefix="data/data/com.termux/files/usr"
proot_bin="$work/proot/$prefix/bin/proot"
loader="$work/proot/$prefix/libexec/proot/loader"
talloc="$(find "$work/talloc/$prefix/lib" -maxdepth 1 -type f -name 'libtalloc.so*' | sort | head -1)"

[ -f "$proot_bin" ] || { echo "proot binary not found in the package" >&2; exit 1; }
[ -f "$loader" ] || { echo "proot loader not found in the package" >&2; exit 1; }
[ -n "$talloc" ] || { echo "libtalloc not found in the package" >&2; exit 1; }

install -m 0755 "$proot_bin" "$dest/libproot.so"
install -m 0755 "$loader" "$dest/libproot-loader.so"
install -m 0755 "$talloc" "$dest/libtalloc.so"

patchelf --replace-needed libtalloc.so.2 libtalloc.so "$dest/libproot.so"
patchelf --remove-rpath "$dest/libproot.so"
patchelf --set-soname libtalloc.so "$dest/libtalloc.so"
patchelf --remove-rpath "$dest/libtalloc.so"

needed() { readelf -d "$1" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'; }
bundled_libs="libtalloc.so"

if needed "$dest/libproot.so" | grep -q "libandroid-shmem"; then
  download libandroid-shmem "$work/shmem"
  shmem="$(find "$work/shmem/$prefix/lib" -maxdepth 1 -type f -name 'libandroid-shmem.so*' | sort | head -1)"
  [ -n "$shmem" ] || { echo "libandroid-shmem not found in the package" >&2; exit 1; }
  install -m 0755 "$shmem" "$dest/libandroid-shmem.so"
  patchelf --set-soname libandroid-shmem.so "$dest/libandroid-shmem.so" 2>/dev/null || true
  patchelf --remove-rpath "$dest/libandroid-shmem.so"
  bundled_libs="$bundled_libs libandroid-shmem.so"
fi

status=0
for lib in "$dest"/lib*.so; do
  [ -f "$lib" ] || continue
  for dep in $(needed "$lib"); do
    case " $system_libs $bundled_libs " in
      *" $dep "*) ;;
      *) echo "$(basename "$lib") needs $dep, which Android does not provide" >&2; status=1 ;;
    esac
  done
done
[ "$status" -eq 0 ] || { echo "The proot package layout changed; the launcher would not start." >&2; exit 1; }

for f in libproot.so libproot-loader.so libtalloc.so; do
  readelf -h "$dest/$f" | grep -qi "Machine:.*${EXPECT_MACHINE:-AArch64}" || { echo "$f is not aarch64" >&2; exit 1; }
done

# The loader is started by proot inside the guest and must not depend on any dynamic loader.
if readelf -l "$dest/libproot-loader.so" | grep -q 'INTERP'; then
  echo "The proot loader is dynamically linked; expected a static executable" >&2
  exit 1
fi

{
  echo "proot packaged from the Termux repository $repo"
  echo "libproot.so needs: $(needed "$dest/libproot.so" | tr '\n' ' ')"
} >"$dest/../proot-origin.txt" 2>/dev/null || true

ls -l "$dest"
