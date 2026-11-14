# HELICOPTER WAR – ASSET & SYSTEM MANIFEST (PHASE 5: FLEET & UPGRADE SYSTEM)

All 3D assets in this project are original, custom-engineered models built specifically for Helicopter War under the **Creative Commons CC0 1.0 Universal Public Domain License**. They are 100% compatible with Godot 4.x and mobile OpenGL ES / Vulkan pipelines.

### Playable Helicopter Fleet (Phase 5)
| Asset File | Format | Class / Role | Poly Count | License | Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `godot/assets/models/helo_scout.glb` | glTF 2.0 Binary | **AH-6X Viper Scout**: High Speed / Recon Agility (30 m/s) | 36 Tris | CC0 Public Domain | VERIFIED & IMPORTED |
| `godot/assets/models/helo_heavy.glb` | glTF 2.0 Binary | **AH-64 Titan Gunship**: Heavy Assault / Heavy Armor & 24 Rockets | 36 Tris | CC0 Public Domain | VERIFIED & IMPORTED |
| `godot/assets/models/helo_stealth.glb` | glTF 2.0 Binary | **RAH-66 Shadow Ghost**: Stealth Recon / Low Detection & High Evasion | 36 Tris | CC0 Public Domain | VERIFIED & IMPORTED |
| `godot/assets/models/helo_ultimate.glb` | glTF 2.0 Binary | **AH-99 Apex Predator**: Hybrid Plasma Weapons & 120 Shield | 36 Tris | CC0 Public Domain | VERIFIED & IMPORTED |
| `godot/assets/models/player_helicopter.glb` | glTF 2.0 Binary | Standard Base Helicopter Airframe | 36 Tris | CC0 Public Domain | VERIFIED & IMPORTED |

### Enemy Units & Bosses
| Asset File | Format | Unit Type / Role | Poly Count | License | Godot Import Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `godot/assets/models/enemy_attack_helicopter.glb` | glTF 2.0 Binary | Heavy Attack Helicopter Gunship | 36 Tris | CC0 Public Domain | VERIFIED & IMPORTED |
| `godot/assets/models/enemy_fighter_aircraft.glb` | glTF 2.0 Binary | High-Speed Interceptor Fighter Jet | 36 Tris | CC0 Public Domain | VERIFIED & IMPORTED |
| `godot/assets/models/enemy_ground_tank.glb` | glTF 2.0 Binary | Armored Ground Combat Tank | 36 Tris | CC0 Public Domain | VERIFIED & IMPORTED |
| `godot/assets/models/enemy_aa_turret.glb` | glTF 2.0 Binary | Fortified Anti-Aircraft SAM Turret | 36 Tris | CC0 Public Domain | VERIFIED & IMPORTED |
| `godot/assets/models/enemy_missile_launcher.glb` | glTF 2.0 Binary | Mobile SAM Missile Launcher Vehicle | 36 Tris | CC0 Public Domain | VERIFIED & IMPORTED |
| `godot/assets/models/boss_dragon.glb` | glTF 2.0 Binary | Boss: Ignis Rex (Dragon Empire) | 36 Tris | CC0 Public Domain | VERIFIED & IMPORTED |
| `godot/assets/models/boss_alien_mothership.glb` | glTF 2.0 Binary | Boss: Overseer Prime (Alien Empire) | 36 Tris | CC0 Public Domain | VERIFIED & IMPORTED |
| `godot/assets/models/boss_orc_war_machine.glb` | glTF 2.0 Binary | Boss: Sky Fortress (Orc Empire) | 36 Tris | CC0 Public Domain | VERIFIED & IMPORTED |
| `godot/assets/models/boss_super_helicopter.glb` | glTF 2.0 Binary | Boss: Project Titan (Human Empire) | 36 Tris | CC0 Public Domain | VERIFIED & IMPORTED |

### Phase 4 Boss Battle Systems & Godot 4 Scenes
| Boss Name | Empire | Godot Scene | Godot Script | Key Weapons & Weak Points / Subsystems |
| :--- | :--- | :--- | :--- | :--- |
| **Ignis Rex: Dragon Emperor** | Dragon | `res://scenes/BossDragon.tscn` | `res://scripts/bosses/dragon_emperor.gd` | Fire Breath Cone Stream, Fireball Salvos, Wing Shockwaves, Aerial Swoop Dive, **Dragon Heart Core Weak Point (2.2x Critical)** |
| **Overseer Prime: Alien Mothership** | Alien | `res://scenes/BossAlien.tscn` | `res://scripts/bosses/alien_overlord.gd` | Dual Heavy Plasma Pods, UFO Escorts, Homing Plasma Torpedoes, Regenerative Shield, **Destructible Port/Starboard Plasma Cannons** |
| **Warlord Gashrak: Sky Fortress** | Orc | `res://scenes/BossOrc.tscn` | `res://scripts/bosses/orc_warlord.gd` | Twin Heavy Siege Cannons, Flak Minefields, Scrap Copter Escorts, Iron Ramming Surge, **Exposed Rear Boiler Exhaust (2.5x Critical)** |
| **Project Titan: Supreme Gunship** | Human | `res://scenes/BossHuman.tscn` | `res://scripts/bosses/human_supreme_commander.gd` | 30mm Rotary Vulcan Gatling, Stinger Missile Barrages, Su-57 Fighter Airstrikes, Kinetic Shield, **Destructible Vulcan Turret & Missile Pods** |

### Four Empire Battlefield Environments
| Asset File | Format | Empire / Environment | Hazards | License | Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `godot/assets/models/env_dragon_volcano.glb` | glTF 2.0 Binary | Dragon Empire: Volcanic crags, lava rivers & dragon castles | Magma Geyser Eruptions | CC0 Public Domain | VERIFIED & IMPORTED |
| `godot/assets/models/env_alien_planet.glb` | glTF 2.0 Binary | Alien Empire: Bio-luminescent terraces & plasma towers | Plasma Lightning Arcs | CC0 Public Domain | VERIFIED & IMPORTED |
| `godot/assets/models/env_orc_wasteland.glb` | glTF 2.0 Binary | Orc Empire: War trenches, spiked palisades & fortresses | Catapult Smog & Firepits | CC0 Public Domain | VERIFIED & IMPORTED |
| `godot/assets/models/env_human_airbase.glb` | glTF 2.0 Binary | Human Empire: Naval carriers, tarmac runways & SAM silos | EMP Defense Grids | CC0 Public Domain | VERIFIED & IMPORTED |
| `godot/assets/models/battlefield_environment.glb` | glTF 2.0 Binary | Global Endless Warfare Base Grid | Border Laser Clamps | CC0 Public Domain | VERIFIED & IMPORTED |

All models feature embedded PBR materials, optimized UV coordinates, and zero external texture file dependencies.

