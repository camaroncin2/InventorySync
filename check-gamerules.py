#!/usr/bin/env python3
"""Parsea gamerules de un level.dat NBT (gzip)."""
import gzip
import sys
import struct

def read_string(data, pos):
    """Lee TAG_String length-prefixed (short BE + bytes)."""
    if pos + 2 > len(data):
        return None, pos
    length = struct.unpack_from('>H', data, pos)[0]
    pos += 2
    if pos + length > len(data):
        return None, pos
    try:
        s = data[pos:pos+length].decode('utf-8')
    except UnicodeDecodeError:
        return None, pos
    return s, pos + length

def main():
    path = sys.argv[1] if len(sys.argv) > 1 else '/opt/minetlan/cretania-survival1/world/level.dat'
    with gzip.open(path, 'rb') as f:
        data = f.read()
    # Buscar "GameRules" compound. Inside the compound, cada entry es TAG_String.
    # Format: 0x08 (TAG_String) + name (short+bytes) + value (short+bytes)
    idx = data.find(b'GameRules')
    if idx < 0:
        print("GameRules tag no encontrado en level.dat")
        return
    print(f"GameRules tag en offset {idx}")
    # Avanzar hasta justo después del nombre "GameRules"
    pos = idx + len(b'GameRules')
    # Leer hasta encontrar TAG_End (0x00) o muchos rules
    rules = {}
    count = 0
    while pos < len(data) and count < 100:
        tag = data[pos]
        if tag == 0x00:
            break
        if tag != 0x08:  # solo TAG_String
            pos += 1
            continue
        pos += 1
        name, pos = read_string(data, pos)
        if name is None or not name:
            break
        value, pos = read_string(data, pos)
        if value is None:
            break
        rules[name] = value
        count += 1
    for k, v in sorted(rules.items()):
        print(f"  {k} = {v}")

if __name__ == '__main__':
    main()
