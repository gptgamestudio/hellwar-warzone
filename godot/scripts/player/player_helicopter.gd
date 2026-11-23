class_name PlayerHelicopter
extends CharacterBody3D

## Signals for HUD and Mission Controller
signal health_changed(current_health: float, max_health: float)
signal armor_changed(current_armor: float, max_armor: float)
signal shield_changed(current_shield: float, max_shield: float)
signal hearts_changed(hearts: int, max_hearts: int, hits_remaining: int, hits_per_heart: int)
signal missile_state_changed(is_ready: bool, cooldown_pct: float, ammo: int, max_ammo: int)
signal rocket_state_changed(is_ready: bool, cooldown_pct: float, ammo: int, max_ammo: int)
signal flare_state_changed(is_ready: bool, cooldown_pct: float)
signal overdrive_state_changed(is_ready: bool, charge_pct: float)
signal missile_ready(is_ready: bool, cooldown_pct: float)
signal player_destroyed()

enum HelicopterType { SCOUT, HEAVY, STEALTH, ULTIMATE }

const HELO_CLASS_MAP := {
	"helo_scout": HelicopterType.SCOUT,
	"SCOUT": HelicopterType.SCOUT,
	"helo_heavy": HelicopterType.HEAVY,
	"HEAVY_GUNSHIP": HelicopterType.HEAVY,
	"helo_stealth": HelicopterType.STEALTH,
	"STEALTH": HelicopterType.STEALTH,
	"helo_ultimate": HelicopterType.ULTIMATE,
	"ULTIMATE_WAR": HelicopterType.ULTIMATE
}

const HELO_MODEL_PATHS := {
	HelicopterType.SCOUT: "res://assets/models/helo_scout.glb",
	HelicopterType.HEAVY: "res://assets/models/helo_heavy.glb",
	HelicopterType.STEALTH: "res://assets/models/helo_stealth.glb",
	HelicopterType.ULTIMATE: "res://assets/models/helo_ultimate.glb"
}

const SKIN_COLORS := {
	"skin_black": Color(0.12, 0.16, 0.21),
	"skin_camo": Color(0.29, 0.27, 0.20),
	"skin_arctic": Color(0.81, 0.85, 0.86),
	"skin_dragon": Color(0.53, 0.05, 0.31),
	"skin_neon": Color(0.05, 0.28, 0.63)
}

@export var helicopter_type: HelicopterType = HelicopterType.SCOUT
@export var current_skin_id: String = "skin_black"

@export_group("Flight Dynamics")
@export var max_speed: float = 30.0
@export var acceleration: float = 72.0
@export var deceleration: float = 58.0
@export var roll_bank_angle_max_deg: float = 22.0
@export var roll_speed: float = 12.0
@export var pitch_tilt_max_deg: float = 10.0

@export_group("Battlefield Bounds")
@export var min_x: float = -240.0
@export var max_x: float = 240.0
@export var min_z: float = -1080.0
@export var max_z: float = 20.0

@export_group("Combat Stats")
@export var max_health: float = 75.0
@export var max_armor: float = 35.0
@export var max_shield: float = 40.0
@export var mg_damage: float = 10.0
@export var missile_damage: float = 65.0
@export var machine_gun_fire_rate: float = 0.12
@export var missile_cooldown: float = 3.2
@export var max_missile_ammo: int = 6
@export var rocket_cooldown: float = 2.0
@export var max_rocket_ammo: int = 12
@export var flare_cooldown: float = 7.0
@export var flare_duration: float = 2.6
@export var max_hearts: int = 5
@export var hits_per_heart: int = 10

@export_group("Node References")
@export var bullet_scene: PackedScene
@export var missile_scene: PackedScene
@export var rocket_scene: PackedScene
@export var left_gun_muzzle: Marker3D
@export var right_gun_muzzle: Marker3D
@export var left_missile_pod: Marker3D
@export var right_missile_pod: Marker3D
@export var main_rotor_mesh: Node3D
@export var tail_rotor_mesh: Node3D
@export var helicopter_body_mesh: Node3D
@export var shield_mesh: Node3D

# Internal Movement State
var input_vector: Vector2 = Vector2.ZERO
var external_move_input: Vector2 = Vector2.ZERO
var current_health: float = 75.0
var current_armor: float = 35.0
var current_shield: float = 40.0
var hearts: int = 5
var hits_remaining_in_heart: int = 10
var invulnerable_timer: float = 1.5
var flare_active_timer: float = 0.0
var overdrive_charge: float = 40.0
var overdrive_beam_timer: float = 0.0
var radar_range: float = 90.0
var current_roll: float = 0.0
var current_pitch: float = 0.0

# Weapons State
var mg_timer: float = 0.0
var missile_timer: float = 0.0
var missile_ammo: int = 6
var missile_recharge_timer: float = 0.0
var rocket_timer: float = 0.0
var rocket_ammo: int = 12
var rocket_recharge_timer: float = 0.0
var flare_timer: float = 0.0
var auto_fire_enabled: bool = true
var target_lock_node: Node3D = null
var is_defeated: bool = false

func apply_configuration_dict(config: Dictionary) -> void:
	var raw_class := str(config.get("helicopterClass", "helo_scout"))
	var resolved_type: HelicopterType = HELO_CLASS_MAP.get(raw_class, HelicopterType.SCOUT)
	current_skin_id = str(config.get("selectedSkin", "skin_black"))
	var up_dict: Variant = config.get("upgrades", {})
	var upgrades: Dictionary = up_dict as Dictionary if up_dict is Dictionary else {}
	apply_configuration(resolved_type, upgrades)
	_apply_airframe_and_skin(resolved_type, current_skin_id)

func apply_configuration(type: HelicopterType, upgrades: Dictionary) -> void:
	helicopter_type = type
	var engine_lvl := clampi(int(upgrades.get("engine", upgrades.get("engineLevel", 1))), 1, 5)
	var armor_lvl := clampi(int(upgrades.get("armor", upgrades.get("armorLevel", 1))), 1, 5)
	var weapon_lvl := clampi(int(upgrades.get("weapon", upgrades.get("weaponLevel", 1))), 1, 5)
	var defense_lvl := clampi(int(upgrades.get("defense", upgrades.get("defenseLevel", 1))), 1, 5)
	var radar_lvl := clampi(int(upgrades.get("radar", upgrades.get("radarLevel", 1))), 1, 5)

	match type:
		HelicopterType.SCOUT:
			max_speed = 30.0 * (1.0 + (engine_lvl - 1) * 0.08)
			max_health = 75.0 * (1.0 + (armor_lvl - 1) * 0.12)
			max_armor = 35.0 * (1.0 + (armor_lvl - 1) * 0.12)
			max_shield = 40.0 * (1.0 + (defense_lvl - 1) * 0.15)
			mg_damage = 10.0 * (1.0 + (weapon_lvl - 1) * 0.10)
			missile_damage = 65.0 * (1.0 + (weapon_lvl - 1) * 0.10)
			max_rocket_ammo = 12 + (weapon_lvl - 1) * 4
			max_missile_ammo = 6
		HelicopterType.HEAVY:
			max_speed = 19.0 * (1.0 + (engine_lvl - 1) * 0.08)
			max_health = 150.0 * (1.0 + (armor_lvl - 1) * 0.12)
			max_armor = 100.0 * (1.0 + (armor_lvl - 1) * 0.12)
			max_shield = 80.0 * (1.0 + (defense_lvl - 1) * 0.15)
			mg_damage = 18.0 * (1.0 + (weapon_lvl - 1) * 0.10)
			missile_damage = 110.0 * (1.0 + (weapon_lvl - 1) * 0.10)
			max_rocket_ammo = 24 + (weapon_lvl - 1) * 4
			max_missile_ammo = 12
		HelicopterType.STEALTH:
			max_speed = 26.0 * (1.0 + (engine_lvl - 1) * 0.08)
			max_health = 90.0 * (1.0 + (armor_lvl - 1) * 0.12)
			max_armor = 50.0 * (1.0 + (armor_lvl - 1) * 0.12)
			max_shield = 60.0 * (1.0 + (defense_lvl - 1) * 0.15)
			mg_damage = 14.0 * (1.0 + (weapon_lvl - 1) * 0.10)
			missile_damage = 95.0 * (1.0 + (weapon_lvl - 1) * 0.10)
			max_rocket_ammo = 16 + (weapon_lvl - 1) * 4
			max_missile_ammo = 8
		HelicopterType.ULTIMATE:
			max_speed = 28.0 * (1.0 + (engine_lvl - 1) * 0.08)
			max_health = 130.0 * (1.0 + (armor_lvl - 1) * 0.12)
			max_armor = 85.0 * (1.0 + (armor_lvl - 1) * 0.12)
			max_shield = 120.0 * (1.0 + (defense_lvl - 1) * 0.15)
			mg_damage = 22.0 * (1.0 + (weapon_lvl - 1) * 0.10)
			missile_damage = 130.0 * (1.0 + (weapon_lvl - 1) * 0.10)
			max_rocket_ammo = 20 + (weapon_lvl - 1) * 4
			max_missile_ammo = 10

	radar_range = 90.0 + float(radar_lvl - 1) * 18.0
	rocket_ammo = max_rocket_ammo
	missile_ammo = max_missile_ammo
	restore_all_hearts()

func restore_all_hearts() -> void:
	hearts = max_hearts
	hits_remaining_in_heart = hits_per_heart
	current_health = max_health
	current_armor = max_armor
	current_shield = max_shield
	invulnerable_timer = 1.8
	is_defeated = false
	visible = true
	_emit_all_vital_signals()

func _emit_all_vital_signals() -> void:
	emit_signal("health_changed", current_health, max_health)
	emit_signal("armor_changed", current_armor, max_armor)
	emit_signal("shield_changed", current_shield, max_shield)
	emit_signal("hearts_changed", hearts, max_hearts, hits_remaining_in_heart, hits_per_heart)
	emit_signal("missile_state_changed", missile_timer <= 0.0 and missile_ammo >= 2, 1.0, missile_ammo, max_missile_ammo)
	emit_signal("rocket_state_changed", rocket_timer <= 0.0 and rocket_ammo >= 4, 1.0, rocket_ammo, max_rocket_ammo)
	emit_signal("flare_state_changed", flare_timer <= 0.0, 1.0)
	emit_signal("overdrive_state_changed", overdrive_charge >= 100.0, overdrive_charge / 100.0)

func _apply_airframe_and_skin(type: HelicopterType, skin_id: String) -> void:
	if helicopter_body_mesh == null:
		return
	var existing_airframe := helicopter_body_mesh.get_node_or_null("AirframeModel")
	var model_path: String = HELO_MODEL_PATHS.get(type, "res://assets/models/player_helicopter.glb")
	if ResourceLoader.exists(model_path):
		var loaded := load(model_path)
		if loaded is PackedScene:
			var inst := (loaded as PackedScene).instantiate()
			if inst is Node3D:
				if existing_airframe != null:
					existing_airframe.queue_free()
				inst.name = "AirframeModel"
				helicopter_body_mesh.add_child(inst)
				existing_airframe = inst
			elif inst != null:
				inst.queue_free()

	var skin_color: Color = SKIN_COLORS.get(skin_id, Color(0.12, 0.16, 0.21))
	if existing_airframe != null:
		_tint_meshes_recursive(existing_airframe, skin_color)

func _tint_meshes_recursive(node: Node, color: Color) -> void:
	if node is MeshInstance3D:
		var mi := node as MeshInstance3D
		var mat := StandardMaterial3D.new()
		mat.albedo_color = color
		mat.metallic = 0.45
		mat.roughness = 0.4
		mi.material_override = mat
	for child in node.get_children():
		_tint_meshes_recursive(child, color)

func _ready() -> void:
	restore_all_hearts()

func set_external_move_input(dir: Vector2) -> void:
	external_move_input = dir

func add_overdrive_charge(amount: float) -> void:
	overdrive_charge = clampf(overdrive_charge + amount, 0.0, 100.0)
	emit_signal("overdrive_state_changed", overdrive_charge >= 100.0, overdrive_charge / 100.0)

func _physics_process(delta: float) -> void:
	if is_defeated:
		return
	if invulnerable_timer > 0.0:
		invulnerable_timer = maxf(0.0, invulnerable_timer - delta)
	if flare_active_timer > 0.0:
		flare_active_timer = maxf(0.0, flare_active_timer - delta)
	if overdrive_beam_timer > 0.0:
		overdrive_beam_timer = maxf(0.0, overdrive_beam_timer - delta)
	if shield_mesh:
		shield_mesh.visible = current_shield > 0.0 or invulnerable_timer > 0.0

	_update_input()
	_apply_movement(delta)
	_apply_banking_and_tilt(delta)
	_spin_rotors(delta)
	_process_weapons(delta)

func _update_input() -> void:
	var move_x := external_move_input.x
	var move_z := external_move_input.y

	if Input.is_action_pressed("move_up"):
		move_z -= 1.0
	if Input.is_action_pressed("move_down"):
		move_z += 1.0
	if Input.is_action_pressed("move_left"):
		move_x -= 1.0
	if Input.is_action_pressed("move_right"):
		move_x += 1.0

	var combined := Vector2(move_x, move_z)
	input_vector = combined.normalized() if combined.length() > 1.0 else combined

func _apply_movement(delta: float) -> void:
	var target_vx := input_vector.x * max_speed
	var target_vz := input_vector.y * max_speed

	velocity.x = move_toward(velocity.x, target_vx, (acceleration if absf(input_vector.x) > 0.01 else deceleration) * delta)
	velocity.z = move_toward(velocity.z, target_vz, (acceleration if absf(input_vector.y) > 0.01 else deceleration) * delta)

	move_and_slide()
	rotation.y = 0.0

	global_position.x = clampf(global_position.x, min_x, max_x)
	global_position.z = clampf(global_position.z, min_z, max_z)

func _apply_banking_and_tilt(delta: float) -> void:
	if helicopter_body_mesh == null:
		return
	var target_roll := -input_vector.x * deg_to_rad(roll_bank_angle_max_deg)
	current_roll = lerp_angle(current_roll, target_roll, roll_speed * delta)
	var target_pitch := input_vector.y * deg_to_rad(pitch_tilt_max_deg)
	current_pitch = lerp_angle(current_pitch, target_pitch, roll_speed * delta)

	helicopter_body_mesh.rotation.z = current_roll
	helicopter_body_mesh.rotation.x = current_pitch
	helicopter_body_mesh.rotation.y = 0.0

func _spin_rotors(delta: float) -> void:
	if main_rotor_mesh:
		main_rotor_mesh.rotate_y(38.0 * delta)
	if tail_rotor_mesh:
		tail_rotor_mesh.rotate_x(45.0 * delta)

func _process_weapons(delta: float) -> void:
	mg_timer += delta
	if auto_fire_enabled and mg_timer >= machine_gun_fire_rate:
		mg_timer = 0.0
		_fire_twin_machine_guns()

	# Recharge ammo over time
	if missile_ammo < max_missile_ammo:
		missile_recharge_timer += delta
		if missile_recharge_timer >= 4.2:
			missile_recharge_timer = 0.0
			missile_ammo = mini(max_missile_ammo, missile_ammo + 1)
	if rocket_ammo < max_rocket_ammo:
		rocket_recharge_timer += delta
		if rocket_recharge_timer >= 2.8:
			rocket_recharge_timer = 0.0
			rocket_ammo = mini(max_rocket_ammo, rocket_ammo + 2)

	if missile_timer > 0.0:
		missile_timer = maxf(0.0, missile_timer - delta)
	var m_pct := 1.0 - (missile_timer / maxf(0.01, missile_cooldown))
	var m_ready := missile_timer <= 0.0 and missile_ammo >= 2
	emit_signal("missile_ready", m_ready, m_pct)
	emit_signal("missile_state_changed", m_ready, m_pct, missile_ammo, max_missile_ammo)

	if rocket_timer > 0.0:
		rocket_timer = maxf(0.0, rocket_timer - delta)
	var r_pct := 1.0 - (rocket_timer / maxf(0.01, rocket_cooldown))
	emit_signal("rocket_state_changed", rocket_timer <= 0.0 and rocket_ammo >= 4, r_pct, rocket_ammo, max_rocket_ammo)

	if flare_timer > 0.0:
		flare_timer = maxf(0.0, flare_timer - delta)
	var f_pct := 1.0 - (flare_timer / maxf(0.01, flare_cooldown))
	emit_signal("flare_state_changed", flare_timer <= 0.0, f_pct)

	if Input.is_action_just_pressed("fire_missile"):
		launch_missiles()
	if Input.is_action_just_pressed("fire_rocket"):
		launch_rockets()
	if Input.is_action_just_pressed("activate_flare"):
		activate_flare()
	if Input.is_action_just_pressed("activate_overdrive"):
		activate_overdrive()

func _fire_twin_machine_guns() -> void:
	if bullet_scene == null or get_parent() == null:
		return
	if left_gun_muzzle:
		_spawn_bullet(left_gun_muzzle.global_position)
	if right_gun_muzzle:
		_spawn_bullet(right_gun_muzzle.global_position)

func _spawn_bullet(spawn_pos: Vector3) -> void:
	var bullet := bullet_scene.instantiate()
	if bullet == null:
		return
	get_parent().add_child(bullet)
	bullet.global_position = spawn_pos
	bullet.global_rotation = Vector3.ZERO
	if "damage" in bullet:
		bullet.damage = mg_damage

func launch_missiles() -> void:
	if missile_timer > 0.0 or missile_ammo < 2 or missile_scene == null or get_parent() == null:
		return
	missile_timer = missile_cooldown
	missile_ammo -= 2
	_acquire_nearest_target()
	if left_missile_pod:
		_spawn_missile(left_missile_pod.global_position)
	if right_missile_pod:
		_spawn_missile(right_missile_pod.global_position)

func _spawn_missile(spawn_pos: Vector3) -> void:
	var missile := missile_scene.instantiate()
	if missile == null:
		return
	get_parent().add_child(missile)
	missile.global_position = spawn_pos
	if "damage" in missile:
		missile.damage = missile_damage
	if missile.has_method("set_target"):
		missile.set_target(target_lock_node)

func launch_rockets() -> void:
	if rocket_timer > 0.0 or rocket_ammo < 4 or rocket_scene == null or get_parent() == null:
		return
	rocket_timer = rocket_cooldown
	rocket_ammo -= 4
	var spreads := [-1.5, -0.5, 0.5, 1.5]
	for sp in spreads:
		var rocket := rocket_scene.instantiate()
		if rocket == null:
			continue
		get_parent().add_child(rocket)
		rocket.global_position = global_position + Vector3(sp * 0.4, -0.3, -2.5)
		if rocket.has_method("set_spread_direction"):
			rocket.set_spread_direction(Vector3(sp * 0.35, 0.0, 0.0))

func activate_flare() -> void:
	if flare_timer > 0.0:
		return
	flare_timer = flare_cooldown
	flare_active_timer = flare_duration
	invulnerable_timer = maxf(invulnerable_timer, flare_duration)

func activate_overdrive() -> void:
	if overdrive_charge < 100.0:
		return
	overdrive_charge = 0.0
	overdrive_beam_timer = 2.0
	emit_signal("overdrive_state_changed", false, 0.0)
	if get_tree() != null:
		for enemy in get_tree().get_nodes_in_group("enemies"):
			if is_instance_valid(enemy) and enemy is Node3D and enemy.has_method("take_damage"):
				if global_position.distance_to((enemy as Node3D).global_position) <= 65.0:
					enemy.take_damage(180.0)

func _acquire_nearest_target() -> void:
	if get_tree() == null:
		target_lock_node = null
		return
	var best_node: Node3D = null
	var best_dist := radar_range
	for enemy in get_tree().get_nodes_in_group("enemies"):
		if not is_instance_valid(enemy) or not (enemy is Node3D):
			continue
		if "is_destroyed" in enemy and enemy.is_destroyed:
			continue
		var d := global_position.distance_to((enemy as Node3D).global_position)
		if d < best_dist:
			best_dist = d
			best_node = enemy as Node3D
	target_lock_node = best_node

func take_damage(amount: float) -> void:
	if is_defeated or invulnerable_timer > 0.0 or flare_active_timer > 0.0:
		return

	if current_shield > 0.0:
		var shield_dmg := minf(current_shield, amount)
		current_shield -= shield_dmg
		amount -= shield_dmg
		emit_signal("shield_changed", current_shield, max_shield)

	if amount > 0.0 and current_armor > 0.0:
		var armor_dmg := minf(current_armor, amount)
		current_armor -= armor_dmg
		amount -= armor_dmg
		emit_signal("armor_changed", current_armor, max_armor)

	if amount > 0.0:
		hits_remaining_in_heart -= 1
		if hits_remaining_in_heart <= 0:
			hearts -= 1
			if hearts > 0:
				hits_remaining_in_heart = hits_per_heart
				invulnerable_timer = 1.2
			else:
				hearts = 0
				hits_remaining_in_heart = 0
		current_health = max_health * (float(hits_remaining_in_heart) / float(hits_per_heart))
		emit_signal("health_changed", current_health, max_health)
		emit_signal("hearts_changed", hearts, max_hearts, hits_remaining_in_heart, hits_per_heart)

	if hearts <= 0:
		is_defeated = true
		emit_signal("player_destroyed")
