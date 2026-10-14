"""Read-only search of saved Anvil blocks for a reproducible flat benchmark origin."""
import io
import struct
import sys
import zlib
from pathlib import Path


def nbt(stream, tag):
    def unpack(fmt):
        return struct.unpack(">" + fmt, stream.read(struct.calcsize(">" + fmt)))[0]
    def string():
        return stream.read(unpack("H")).decode("utf-8")
    if tag == 1: return unpack("b")
    if tag == 2: return unpack("h")
    if tag == 3: return unpack("i")
    if tag == 4: return unpack("q")
    if tag == 5: return unpack("f")
    if tag == 6: return unpack("d")
    if tag == 7: return stream.read(unpack("i"))
    if tag == 8: return string()
    if tag == 9:
        child, length = unpack("B"), unpack("i")
        return [nbt(stream, child) for _ in range(length)]
    if tag == 10:
        value = {}
        while True:
            child = unpack("B")
            if child == 0: return value
            name = string()
            value[name] = nbt(stream, child)
    if tag in (11, 12):
        return [unpack("i" if tag == 11 else "q") for _ in range(unpack("i"))]
    raise ValueError(tag)


world = Path(sys.argv[1])
chunks = {}
for cx in range(28, 36):
    for cz in range(22, 30):
        region = world / "region" / f"r.{cx // 32}.{cz // 32}.mca"
        if not region.exists(): continue
        with region.open("rb") as file:
            file.seek(((cx & 31) + (cz & 31) * 32) * 4)
            offset = int.from_bytes(file.read(3), "big")
            if not offset: continue
            file.seek(offset * 4096)
            size = int.from_bytes(file.read(4), "big")
            compression = file.read(1)[0]
            raw = file.read(size - 1)
        raw = zlib.decompress(raw, 31 if compression == 1 else 15)
        stream = io.BytesIO(raw)
        assert stream.read(1) == b"\x0a"
        stream.read(int.from_bytes(stream.read(2), "big"))
        level = nbt(stream, 10)["Level"]
        chunks[cx, cz] = {section["Y"]: section for section in level.get("Sections", [])}


def block(x, y, z):
    section = chunks.get((x // 16, z // 16), {}).get(y // 16)
    if section is None: return 0
    index = ((y & 15) * 16 + (z & 15)) * 16 + (x & 15)
    value = section["Blocks"][index]
    if "Add" in section:
        value |= ((section["Add"][index // 2] >> ((index & 1) * 4)) & 15) << 8
    return value


air = {0, 31, 32, 37, 38, 39, 40, 78, 175}
print(f"loaded_saved_chunks={len(chunks)}")
for half in (2, 1, 0):
    found = None
    for radius in range(0, 49):
        if found: break
        for dx in range(-radius, radius + 1):
            if found: break
            for dz in range(-radius, radius + 1):
                if max(abs(dx), abs(dz)) != radius: continue
                x, z = 513 + dx, 411 + dz
                for y in range(110, int(sys.argv[2]) - 1 if len(sys.argv) > 2 else 50, -1):
                    if all(block(x + ox, y - 1, z + oz) in (1, 2, 3)
                           and block(x + ox, y, z + oz) in air
                           and block(x + ox, y + 1, z + oz) in air
                           for ox in range(-half, half + 1) for oz in range(-half, half + 1)):
                        found = (x, y, z)
                        break
                if found: break
    print(f"flat_width={half * 2 + 1} position={found}")
