#!/usr/bin/env python3
"""
3D Procedural glTF 2.0 (.glb) Asset Generator for Helicopter War - Phase 3
Generates all 4 Empire battlefield environments and units.
All models are created originally for this project under CC0 1.0 Universal Public Domain.
"""

import struct
import json
import os

def create_box_mesh(name, sx, sy, sz, color_rgba):
    hx, hy, hz = sx / 2.0, sy / 2.0, sz / 2.0
    vertices = [
        # Front face
        -hx, -hy,  hz,   hx, -hy,  hz,   hx,  hy,  hz,  -hx,  hy,  hz,
        # Back face
        -hx, -hy, -hz,  -hx,  hy, -hz,   hx,  hy, -hz,   hx, -hy, -hz,
        # Top face
        -hx,  hy, -hz,  -hx,  hy,  hz,   hx,  hy,  hz,   hx,  hy, -hz,
        # Bottom face
        -hx, -hy, -hz,   hx, -hy, -hz,   hx, -hy,  hz,  -hx, -hy,  hz,
        # Right face
         hx, -hy, -hz,   hx,  hy, -hz,   hx,  hy,  hz,   hx, -hy,  hz,
        # Left face
        -hx, -hy, -hz,  -hx, -hy,  hz,  -hx,  hy,  hz,  -hx,  hy, -hz,
    ]
    indices = [
        0, 1, 2,  0, 2, 3,
        4, 5, 6,  4, 6, 7,
        8, 9, 10, 8, 10, 11,
        12, 13, 14, 12, 14, 15,
        16, 17, 18, 16, 18, 19,
        20, 21, 22, 20, 22, 23
    ]
    return build_glb(name, vertices, indices, color_rgba)

def build_glb(model_name, vertices, indices, color_rgba):
    pos_bytes = struct.pack(f'{len(vertices)}f', *vertices)
    idx_bytes = struct.pack(f'{len(indices)}H', *indices)

    while len(idx_bytes) % 4 != 0:
        idx_bytes += b'\x00'
    while len(pos_bytes) % 4 != 0:
        pos_bytes += b'\x00'

    bin_data = idx_bytes + pos_bytes
    idx_offset = 0
    idx_length = len(indices) * 2
    pos_offset = len(idx_bytes)
    pos_length = len(vertices) * 4

    min_x = min(vertices[0::3])
    max_x = max(vertices[0::3])
    min_y = min(vertices[1::3])
    max_y = max(vertices[1::3])
    min_z = min(vertices[2::3])
    max_z = max(vertices[2::3])

    gltf = {
        "asset": {"version": "2.0", "generator": "HelicopterWar Asset Generator"},
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0, "name": model_name}],
        "meshes": [{
            "name": model_name,
            "primitives": [{
                "attributes": {"POSITION": 1},
                "indices": 0,
                "material": 0
            }]
        }],
        "materials": [{
            "name": f"{model_name}_Mat",
            "pbrMetallicRoughness": {
                "baseColorFactor": color_rgba,
                "metallicFactor": 0.5,
                "roughnessFactor": 0.4
            }
        }],
        "accessors": [
            {
                "bufferView": 0,
                "byteOffset": 0,
                "componentType": 5123,
                "count": len(indices),
                "type": "SCALAR",
                "max": [max(indices)],
                "min": [min(indices)]
            },
            {
                "bufferView": 1,
                "byteOffset": 0,
                "componentType": 5126,
                "count": len(vertices) // 3,
                "type": "VEC3",
                "max": [max_x, max_y, max_z],
                "min": [min_x, min_y, min_z]
            }
        ],
        "bufferViews": [
            {
                "buffer": 0,
                "byteOffset": idx_offset,
                "byteLength": idx_length,
                "target": 34963
            },
            {
                "buffer": 0,
                "byteOffset": pos_offset,
                "byteLength": pos_length,
                "target": 34962
            }
        ],
        "buffers": [{
            "byteLength": len(bin_data)
        }]
    }

    json_str = json.dumps(gltf, separators=(',', ':'))
    json_bytes = json_str.encode('utf-8')
    while len(json_bytes) % 4 != 0:
        json_bytes += b' '

    chunk0_len = len(json_bytes)
    chunk0_type = 0x4E4F534A
    chunk1_len = len(bin_data)
    chunk1_type = 0x004E4942

    total_length = 12 + (8 + chunk0_len) + (8 + chunk1_len)
    header = struct.pack('<4sII', b'glTF', 2, total_length)
    chunk0_hdr = struct.pack('<II', chunk0_len, chunk0_type)
    chunk1_hdr = struct.pack('<II', chunk1_len, chunk1_type)

    return header + chunk0_hdr + json_bytes + chunk1_hdr + bin_data

def main():
    target_dir = "godot/assets/models"
    os.makedirs(target_dir, exist_ok=True)

    models = [
        # Units
        ("player_helicopter", 3.2, 1.4, 6.5, [0.18, 0.28, 0.38, 1.0]),
        ("helo_scout", 2.4, 1.1, 5.0, [0.95, 0.75, 0.10, 1.0]), # Scout: High speed, agile, yellow-gold
        ("helo_heavy", 4.2, 1.8, 7.5, [0.18, 0.28, 0.22, 1.0]), # Heavy Gunship: Armored, olive drab
        ("helo_stealth", 3.0, 1.0, 6.2, [0.08, 0.10, 0.14, 1.0]), # Stealth: Sleek, matte carbon black
        ("helo_ultimate", 3.8, 1.6, 7.0, [0.00, 0.85, 0.95, 1.0]), # Ultimate: Cyber cyan & plasma white
        ("enemy_attack_helicopter", 3.0, 1.3, 6.0, [0.65, 0.15, 0.15, 1.0]),
        ("enemy_fighter_aircraft", 4.5, 0.8, 7.2, [0.12, 0.45, 0.75, 1.0]),
        ("enemy_ground_tank", 3.6, 1.8, 5.0, [0.42, 0.38, 0.25, 1.0]),
        ("enemy_aa_turret", 2.6, 2.8, 2.6, [0.35, 0.40, 0.35, 1.0]),
        ("enemy_missile_launcher", 3.2, 2.2, 5.8, [0.55, 0.35, 0.15, 1.0]),

        # Bosses
        ("boss_dragon", 12.0, 4.5, 14.0, [0.95, 0.22, 0.05, 1.0]),
        ("boss_alien_mothership", 16.0, 3.5, 16.0, [0.75, 0.10, 0.95, 1.0]),
        ("boss_orc_war_machine", 14.0, 6.0, 15.0, [0.72, 0.32, 0.08, 1.0]),
        ("boss_super_helicopter", 10.0, 3.8, 15.5, [0.10, 0.50, 0.85, 1.0]),

        # 4 Empire Battlefield Environments
        ("env_dragon_volcano", 28.0, 1.2, 50.0, [0.32, 0.08, 0.04, 1.0]),
        ("env_alien_planet", 28.0, 1.2, 50.0, [0.12, 0.06, 0.26, 1.0]),
        ("env_orc_wasteland", 28.0, 1.2, 50.0, [0.24, 0.18, 0.12, 1.0]),
        ("env_human_airbase", 28.0, 1.2, 50.0, [0.16, 0.22, 0.28, 1.0]),
        ("battlefield_environment", 30.0, 0.5, 60.0, [0.15, 0.18, 0.22, 1.0])
    ]

    for name, sx, sy, sz, color in models:
        glb_data = create_box_mesh(name, sx, sy, sz, color)
        path = os.path.join(target_dir, f"{name}.glb")
        with open(path, "wb") as f:
            f.write(glb_data)
        print(f"Generated: {path} ({len(glb_data)} bytes)")

if __name__ == "__main__":
    main()
