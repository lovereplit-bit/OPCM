#!/usr/bin/env bash
# Downloads proot and its loader from the Termux package repository and turns them into the four files the app
# executes from its native library directory:
#
#   libproot.so           the launcher (Termux's proot, byte for byte)
#   libproot-loader.so    its helper, started inside the guest
#   libtalloc.so          the talloc library proot needs
#   libandroid-shmem.so   the shared memory helper library proot needs (if required)
#
# Android only unpacks files named lib*.so into that directory, so the files are renamed with plain copies.
# NOTHING is rewritten with patchelf: on these exact binaries (lld layout with separate RELRO segments, mixed
# vaddr offsets) patchelf's section rewrite appended a PT_LOAD whose file offset and virtual address are not
# congruent modulo the page size - the ELF gABI requirement every loader relies on. The bytes of such a segment
# land at shifted addresses at load time, the dynamic linker reads garbage as its string table, and the process
# dies with a bare SIGSEGV before printing a single line. Every launcher patched that way crashed on every
# device the app was installed on. The two renames that patchelf used to do are done differently instead:
#
#   - libtalloc keeps its DT_SONAME "libtalloc.so.2", and the app resolves it by putting a symlink
#     files/launcher-libs/libtalloc.so.2 -> <nativeLibDir>/libtalloc.so in front of nativeLibDir on
#     LD_LIBRARY_PATH (exactly how Termux itself resolves it);
#   - libandroid-shmem already carries the name we need, and proot's DT_NEEDED entries are left untouched.
#
# The output is verified instead of trusted: every produced file must pass check-elf-loadable.py (all PT_LOAD
# segments congruent modulo 4096, 16384 and their own alignment), so no rewrite of this script can ever ship
# a loader that crashes before its first line of output again.
#
# Usage: runtime/fetch-proot.sh <dest-dir>      (needs curl, dpkg-deb, readelf, python3, xz)
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

# Libraries every Android device provides, plus the ones this script ships next to proot. Anything else in a
# NEEDED entry means the package layout changed.
system_libs="libc.so libm.so libdl.so liblog.so"
bundled_libs="libtalloc.so.2 libandroid-shmem.so"

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

download() { # download <package> <extract-dir> [version]
  local pkg="$1" dir="$2" pin="${3:-}" version file sha
  if [ -n "$pin" ]; then
    mapfile -t info < <(python3 "$here/termux_index.py" "$work/Packages" "$pkg" "$pin")
  else
    mapfile -t info < <(python3 "$here/termux_index.py" "$work/Packages" "$pkg")
  fi
  version="${info[0]}"
  file="${info[1]}"
  sha="${info[2]}"

  curl -fsSL --retry 3 --max-time 300 -o "$work/$pkg.deb" "$repo/$file"
  echo "$sha  $work/$pkg.deb" | sha256sum -c - >/dev/null || { echo "Checksum mismatch for $pkg" >&2; exit 1; }
  echo "$pkg $version ($sha)"
  mkdir -p "$dir"
  dpkg-deb -x "$work/$pkg.deb" "$dir"
}

download proot "$work/proot" "$PROOT_VERSION"
download libtalloc "$work/talloc" "$LIBTALLOC_VERSION"

prefix="data/data/com.termux/files/usr"
proot_bin="$work/proot/$prefix/bin/proot"
loader="$work/proot/$prefix/libexec/proot/loader"
talloc="$(find "$work/talloc/$prefix/lib" -maxdepth 1 -type f -name 'libtalloc.so*' | sort | head -1)"

[ -f "$proot_bin" ] || { echo "proot binary not found in the package" >&2; exit 1; }
[ -f "$loader" ] || { echo "proot loader not found in the package" >&2; exit 1; }
[ -n "$talloc" ] || { echo "libtalloc not found in the package" >&2; exit 1; }

# Plain copies under the lib*.so names Android extracts; the ELF contents stay byte for byte as Termux
# built them. See the header comment for why nothing may be rewritten here.
install -m 0755 "$proot_bin" "$dest/libproot.so"
install -m 0755 "$loader" "$dest/libproot-loader.so"
install -m 0755 "$talloc" "$dest/libtalloc.so"

needed() { readelf -d "$1" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'; }

if needed "$dest/libproot.so" | grep -q "libandroid-shmem"; then
  download libandroid-shmem "$work/shmem" "$LIBANDROID_SHMEM_VERSION"
  shmem="$(find "$work/shmem/$prefix/lib" -maxdepth 1 -type f -name 'libandroid-shmem.so*' | sort | head -1)"
  [ -n "$shmem" ] || { echo "libandroid-shmem not found in the package" >&2; exit 1; }
  install -m 0755 "$shmem" "$dest/libandroid-shmem.so"
else
  bundled_libs="libtalloc.so.2"
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

# The gate this script exists behind: no file leaves here with a PT_LOAD a loader cannot map.
# See check-elf-loadable.py for what it catches and why that must never ship.
python3 "$here/check-elf-loadable.py" "$dest"/lib*.so >/dev/null || {
  echo "A launcher library has PT_LOAD segments a loader cannot map; refusing to package it." >&2
  exit 1
}

{
  echo "proot packaged from the Termux repository $repo"
  echo "binaries are unmodified Termux builds; libtalloc.so.2 is resolved by the app's launcher-libs symlink"
  echo "libproot.so needs: $(needed "$dest/libproot.so" | tr '\n' ' ')"
} >"$dest/../proot-origin.txt" 2>/dev/null || true

ls -l "$dest"
