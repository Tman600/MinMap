"""
Builds app/src/main/assets/starbase/mega-bay.glb: a Mega Bay as an open shell (back and end walls,
roof, a header over the door, and a floor), so you can see into the bay from the door side.

Size from OpenStreetMap ("Mega Bay", "Mega Bay 2"): 53.5 m by 38 m, 100 m tall. The model is in
Mapbox's frame: x east, y up, z south, origin at the centre of the floor. Its long side runs along x
and the open door side faces +z (south); MinMap turns it to each bay's real heading.

Run from the repository root:  python tools/make-mega-bay.py
"""
import json
import struct
import sys
from pathlib import Path

LENGTH, WIDTH, HEIGHT = 53.5, 38.0, 100.0
WALL = 1.5
HEADER = 10.0  # wall left above the door opening
FLOOR = 0.3

WALL_COLOR = [0.78, 0.79, 0.8, 1.0]  # the bays' pale cladding
FLOOR_COLOR = [0.42, 0.42, 0.43, 1.0]  # shop floor concrete


def box(x0, x1, y0, y1, z0, z1):
    """Positions, normals and indices for an axis-aligned box, each face with its own normal."""
    faces = [
        ((1, 0, 0), [(x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (x1, y0, z1)]),
        ((-1, 0, 0), [(x0, y0, z1), (x0, y1, z1), (x0, y1, z0), (x0, y0, z0)]),
        ((0, 1, 0), [(x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)]),
        ((0, -1, 0), [(x0, y0, z1), (x0, y0, z0), (x1, y0, z0), (x1, y0, z1)]),
        ((0, 0, 1), [(x1, y0, z1), (x1, y1, z1), (x0, y1, z1), (x0, y0, z1)]),
        ((0, 0, -1), [(x0, y0, z0), (x0, y1, z0), (x1, y1, z0), (x1, y0, z0)]),
    ]
    pos, nor, idx = [], [], []
    for normal, quad in faces:
        base = len(pos)
        pos += quad
        nor += [normal] * 4
        idx += [base, base + 1, base + 2, base, base + 2, base + 3]
    return pos, nor, idx


def merge(boxes):
    pos, nor, idx = [], [], []
    for p, n, i in boxes:
        idx += [len(pos) + k for k in i]
        pos += p
        nor += n
    return pos, nor, idx


def main(out):
    x, z = LENGTH / 2, WIDTH / 2
    walls = merge([
        box(-x, x, 0, HEIGHT, -z, -z + WALL),                 # back wall (north)
        box(-x, -x + WALL, 0, HEIGHT, -z, z),                 # west end
        box(x - WALL, x, 0, HEIGHT, -z, z),                   # east end
        box(-x, x, HEIGHT - WALL, HEIGHT, -z, z),             # roof
        box(-x, x, HEIGHT - HEADER, HEIGHT, z - WALL, z),     # header over the door (south)
    ])
    floor = merge([box(-x + WALL, x - WALL, 0, FLOOR, -z + WALL, z)])

    blob = bytearray()
    views, accessors, primitives = [], [], []

    def add(data, target, component, count, kind, bounds=None):
        while len(blob) % 4:
            blob.append(0)
        views.append({"buffer": 0, "byteOffset": len(blob), "byteLength": len(data), "target": target})
        blob.extend(data)
        acc = {"bufferView": len(views) - 1, "componentType": component, "count": count, "type": kind}
        if bounds:
            acc["min"], acc["max"] = bounds
        accessors.append(acc)
        return len(accessors) - 1

    for material, (pos, nor, idx) in enumerate((walls, floor)):
        lo = [min(p[i] for p in pos) for i in range(3)]
        hi = [max(p[i] for p in pos) for i in range(3)]
        p = add(b"".join(struct.pack("<3f", *v) for v in pos), 34962, 5126, len(pos), "VEC3", (lo, hi))
        n = add(b"".join(struct.pack("<3f", *v) for v in nor), 34962, 5126, len(nor), "VEC3")
        i = add(struct.pack(f"<{len(idx)}H", *idx), 34963, 5123, len(idx), "SCALAR")
        primitives.append({"attributes": {"POSITION": p, "NORMAL": n}, "indices": i, "material": material})

    gltf = {
        "asset": {"version": "2.0", "generator": "MinMap tools/make-mega-bay.py"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0, "name": "mega-bay"}],
        "meshes": [{"primitives": primitives}],
        "materials": [
            {"name": "cladding", "pbrMetallicRoughness": {"baseColorFactor": WALL_COLOR, "metallicFactor": 0.0, "roughnessFactor": 0.8}},
            {"name": "floor", "pbrMetallicRoughness": {"baseColorFactor": FLOOR_COLOR, "metallicFactor": 0.0, "roughnessFactor": 0.9}},
        ],
        "accessors": accessors,
        "bufferViews": views,
        "buffers": [{"byteLength": len(blob)}],
    }
    js = json.dumps(gltf, separators=(",", ":")).encode()
    js += b" " * (-len(js) % 4)
    while len(blob) % 4:
        blob.append(0)
    total = 12 + 8 + len(js) + 8 + len(blob)
    out.write_bytes(
        b"glTF" + struct.pack("<II", 2, total)
        + struct.pack("<I", len(js)) + b"JSON" + js
        + struct.pack("<I", len(blob)) + b"BIN\0" + bytes(blob)
    )
    print(f"wrote {out} ({total} bytes)")


if __name__ == "__main__":
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    main(root / "app/src/main/assets/starbase/mega-bay.glb")
