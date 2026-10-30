#!/usr/bin/env python3
"""Reject ELF files whose PT_LOAD segments cannot be mapped at page granularity.

Every loader — the kernel for exec'd binaries, bionic and glibc for shared
libraries — maps a PT_LOAD by taking the page-aligned start of ``p_vaddr``
and the page-aligned start of ``p_offset`` and assuming the two page offsets
are equal. The ELF gABI guarantees exactly that::

    p_offset % p_align == p_vaddr % p_align

A PT_LOAD that breaks the congruence therefore does not fail loudly: its bytes
land at shifted addresses, the loader reads garbage as dynamic-table strings
or relocations, and the process dies with a bare SIGSEGV before a single line
of output. That is not a theoretical worry: rewriting these Termux binaries
with patchelf (--set-soname/--remove-rpath/--replace-needed) appended exactly
such a segment — offset 0x3ede0 against vaddr 0x50000 — and the resulting APK
crashed on every device it was installed on, with no diagnostics at all.

The check also enforces 16 KiB congruence, so the shipped libraries keep
loading on the 16 KiB-page kernels of Android 15+ devices.

Usage: check-elf-loadable.py <file> [<file> ...]
Exit 0 when every PT_LOAD of every file is congruent modulo 0x1000, 0x4000 and
its own p_align; exit 1 (with a report) otherwise.
"""
import struct
import sys

PAGE_SIZES = (0x1000, 0x4000)


def check(path):
    with open(path, "rb") as fh:
        data = fh.read()

    if data[:4] != b"\x7fELF":
        print(f"{path}: not an ELF file", file=sys.stderr)
        return False

    if data[4] != 2:
        print(f"{path}: only ELF64 files are checked here", file=sys.stderr)
        return False

    e_phoff, = struct.unpack_from("<Q", data, 0x20)
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)

    bad = []
    loads = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, _p_flags = struct.unpack_from("<II", data, off)
        p_offset, p_vaddr, _p_paddr, p_filesz, p_memsz, p_align = struct.unpack_from(
            "<QQQQQQ", data, off + 8
        )
        if p_type != 1:  # PT_LOAD
            continue
        loads.append((i, p_offset, p_vaddr, p_filesz, p_memsz, p_align))
        if p_align >= 0x1000 and p_offset % p_align != p_vaddr % p_align:
            bad.append((i, f"mod p_align ({p_align:#x})", p_offset, p_vaddr))
        for page in PAGE_SIZES:
            if p_offset % page != p_vaddr % page:
                bad.append((i, f"mod page {page:#x}", p_offset, p_vaddr))

    for i, o, v, fs, ms, al in loads:
        print(f"{path}: LOAD[{i}] off={o:#x} vaddr={v:#x} filesz={fs:#x} align={al:#x}")
    for i, why, o, v in bad:
        print(f"{path}: BROKEN LOAD[{i}] ({why}): off={o:#x} vaddr={v:#x}", file=sys.stderr)
    if not bad:
        print(f"{path}: OK ({len(loads)} PT_LOAD segments, all congruent)")

    return not bad


def main(argv):
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 64

    ok = True
    for path in argv[1:]:
        ok = check(path) and ok

    print("VERDICT:", "LOADABLE" if ok else "CORRUPTED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
