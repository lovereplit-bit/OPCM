#!/usr/bin/env python3
"""Reads a Debian `Packages` index (as published by the Termux repositories) and prints one package's download details.

Usage: termux_index.py <Packages-file> <package> [version]
Prints three lines: version, filename (relative to the repository root), sha256. Exits 1 when the package is missing
or the requested pin is not published. Without a version the newest entry is picked; with one, it must match exactly,
so a silently republished upstream build fails the build instead of shipping.
"""
import re
import sys


def parse(text):
    for block in re.split(r"\n\s*\n", text):
        fields = {}
        key = None

        for line in block.splitlines():
            if line[:1] in (" ", "\t") and key:
                fields[key] += "\n" + line.strip()
            elif ":" in line:
                key, _, value = line.partition(":")
                fields[key.strip()] = value.strip()

        if "Package" in fields:
            yield fields


def version_key(version):
    # Good enough to pick the newest of a few revisions of the same package: numbers compare as numbers.
    return [int(p) if p.isdigit() else p for p in re.split(r"(\d+)", version) if p]


def find(text, name, version=None):
    found = [f for f in parse(text) if f.get("Package") == name and f.get("Filename") and f.get("SHA256")]

    if version is not None:
        found = [f for f in found if f.get("Version") == version]
    elif found:
        found.sort(key=lambda f: [(0, p) if isinstance(p, int) else (1, p) for p in version_key(f.get("Version", ""))])
        found = found[-1:]

    return found[0] if found else None


def main(argv):
    if len(argv) not in (3, 4):
        print(__doc__, file=sys.stderr)
        return 64

    with open(argv[1], encoding="utf-8", errors="replace") as handle:
        entry = find(handle.read(), argv[2], argv[3] if len(argv) == 4 else None)

    if entry is None:
        pin = f" version {argv[3]} of" if len(argv) == 4 else ""
        print(f"package{pin} {argv[2]} not found in {argv[1]}", file=sys.stderr)
        return 1

    if not re.fullmatch(r"[0-9a-f]{64}", entry["SHA256"]) or ".." in entry["Filename"] or entry["Filename"].startswith("/"):
        print("the index entry looks malformed", file=sys.stderr)
        return 1

    print(entry.get("Version", ""))
    print(entry["Filename"])
    print(entry["SHA256"])

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
