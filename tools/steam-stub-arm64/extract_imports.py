#!/usr/bin/env python3
"""Build steamworks_net_imports.tsv from a decompiled Steamworks.NET NativeMethods.cs.

Decompile the game's own com.rlabrecque.steamworks.net.dll (for example with ilspycmd -p) outside the
repository and pass the resulting Steamworks/NativeMethods.cs. Only names and return types are kept;
no game or Steamworks.NET code is copied.

Each row: library, entry point, managed return type, ABI class of the return value
(void, f32, f64, struct:<name> for structs returned in registers or memory, u64 for everything else).

Usage: python extract_imports.py NativeMethods.cs [output.tsv]
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
STRUCT_RETURNS = {"SteamIPAddress_t", "InputDigitalActionData_t", "InputAnalogActionData_t", "InputMotionData_t"}
# Managed return types known to travel in x0 (integers, pointers, enums, and single-integer wrappers).
PATTERN = re.compile(
    r'\[DllImport\("(?P<lib>[^"]+)"(?P<attrs>[^\]]*)\)\]\s*'
    r'(?:\[return:[^\]]*\]\s*)?'
    r'public static extern (?P<ret>[\w.\[\]]+) (?P<name>\w+)\(', re.S)


def abi_of(ret):
    if ret == "void":
        return "void"
    if ret == "float":
        return "f32"
    if ret == "double":
        return "f64"
    if ret in STRUCT_RETURNS:
        return "struct:" + ret
    return "u64"


def main():
    src = open(sys.argv[1], encoding="utf-8-sig").read()
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "steamworks_net_imports.tsv")
    rows = set()
    for m in PATTERN.finditer(src):
        entry = re.search(r'EntryPoint\s*=\s*"([^"]+)"', m.group("attrs"))
        rows.add((m.group("lib"), entry.group(1) if entry else m.group("name"), m.group("ret"), abi_of(m.group("ret"))))
    with open(out, "w", newline="\n", encoding="utf-8") as f:
        f.write("# library\tentry_point\tmanaged_return\tabi_return\n")
        for row in sorted(rows):
            f.write("\t".join(row) + "\n")
    print(f"{len(rows)} imports -> {out}")


if __name__ == "__main__":
    main()
