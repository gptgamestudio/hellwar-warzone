class_name PlayerHelicopter
extends CharacterBody3D

## Signals for HUD and Game Manager
signal health_changed(current_health: float, max_health: float)
signal armor_changed(current_armor: float, max_armor: float)
signal shield_changed(current_shield: float, max_shield: float)
signal missile_ready(is_ready: bool, cooldown_pct: float)
signal player_destroyed()

enum HelicopterType { SCOUT, HEAVY, STEALTH, ULTIMATE }

@export var helicopter_type: HelicopterType = HelicopterType.SCOUT
@export var current_skin_id: String = "skin_black"

@export_group("Flight Dynamics")
@export var max_speed: float = 24.0
@export var acceleration: float = 55.0
@export var deceleration: float = 40.0
@export var roll_bank_angle_max_deg: float = 18.0
@export var roll_speed: float = 12.0
@export var pitch_tilt_max_deg: float = 8.0

@export_group("Battlefield Bounds")
@export var min_x: float = -14.0
@export var max_x: float = 14.0
@export var min_z: float = -22.0
@export var max_z: float = 18.0

@export_group("Combat Stats")
@export var max_health: float = 100.0
@export var max_armor: float = 50.0
@export var max_shield: float = 40.0
@export var mg_damage: float = 12.0
@export var missile_damage: float = 75.0
@export var machine_gun_fire_rate: float = 0.12 # Seconds between twin cannon shots
@export var missile_cooldown: float = 3.5
@export var max_rocket_ammo: int = 16

@export_group("Node References")
@export var bullet_scene: PackedScene
@export var missile_scene: PackedScene
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
var current_health: float = 100.0
var current_armor: float = 50.0
var current_shield: float = 40.0
var current_roll: float = 0.0
var current_pitch: float = 0.0

# Weapons State
var mg_timer: float = 0.0
var missile_timer: float = 0.0
var auto_fire_enabled: bool = true
var target_lock_node: Node3D = null

func apply_configuration(type: HelicopterType, upgrades: Dictionary) -> void:
	helicopter_type = type
	var engine_lvl = upgrades.get("engine", 1)
	var armor_lvl = upgrades.get("armor", 1)
	var weapon_lvl = upgrades.get("weapon", 1)
	var defense_lvl = upgrades.get("defense", 1)

	match type:
		HelicopterType.SCOUT:
			max_speed = 30.0 * (1.0 + (engine_lvl - 1) * 0.08)
			max_health = 75.0 * (1.0 + (armor_lvl - 1) * 0.12)
			max_armor = 35.0 * (1.0 + (armor_lvl - 1) * 0.12)
			max_shield = 40.0 * (1.0 + (defense_lvl - 1) * 0.15)
			mg_damage = 10.0 * (1.0 + (weapon_lvl - 1) * 0.10)
		HelicopterType.HEAVY:
			max_speed = 19.0 * (1.0 + (engine_lvl - 1) * 0.08)
			max_health = 150.0 * (1.0 + (armor_lvl - 1) * 0.12)
			max_armor = 100.0 * (1.0 + (armor_lvl - 1) * 0.12)
			max_shield = 80.0 * (1.0 + (defense_lvl - 1) * 0.15)
			mg_damage = 18.0 * (1.0 + (weapon_lvl - 1) * 0.10)
		HelicopterType.STEALTH:
			max_speed = 26.0 * (1.0 + (engine_lvl - 1) * 0.08)
			max_health = 90.0 * (1.0 + (armor_lvl - 1) * 0.12)
			max_armor = 50.0 * (1.0 + (armor_lvl - 1) * 0.12)
			max_shield = 60.0 * (1.0 + (defense_lvl - 1) * 0.15)
			mg_damage = 14.0 * (1.0 + (weapon_lvl - 1) * 0.10)
		HelicopterType.ULTIMATE:
			max_speed = 28.0 * (1.0 + (engine_lvl - 1) * 0.08)
			max_health = 130.0 * (1.0 + (armor_lvl - 1) * 0.12)
			max_armor = 85.0 * (1.0 + (armor_lvl - 1) * 0.12)
			max_shield = 120.0 * (1.0 + (defense_lvl - 1) * 0.15)
			mg_damage = 22.0 * (1.0 + (weapon_lvl - 1) * 0.10)

	current_health = max_health
	current_armor = max_armor
	current_shield = max_shield
	emit_signal("health_changed", current_health, max_health)
	emit_signal("armor_changed", current_armor, max_armor)
	emit_signal("shield_changed", current_shield, max_shield)

func _ready() -> void:
	current_health = max_health
	current_armor = max_armor
	emit_signal("health_changed", current_health, max_health)
	emit_signal("armor_changed", current_armor, max_armor)

func _physics_process(delta: float) -> void:
	_update_input()
	_apply_movement(delta)
	_apply_banking_and_tilt(delta)
	_spin_rotors(delta)
	_process_weapons(delta)

## Process independent 4-direction controls
func _update_input() -> void:
	var move_x: float = 0.0
	var move_z: float = 0.0

	# UP moves forward (-Z), DOWN moves backward (+Z)
	if Input.is_action_pressed("move_up"):
		move_z -= 1.0
	if Input.is_action_pressed("move_down"):
		move_z += 1.0

	# LEFT moves sideways left (-X), RIGHT moves sideways right (+X)
	if Input.is_action_pressed("move_left"):
		move_x -= 1.0
	if Input.is_action_pressed("move_right"):
		move_x += 1.0

	# Normalizes diagonal input for smooth consistent velocity
	input_vector = Vector2(move_x, move_z).normalized()

## Applies acceleration, deceleration, and clamps strictly to battlefield
func _apply_movement(delta: float) -> void:
	var target_velocity_x = input_vector.x * max_speed
	var target_velocity_z = input_vector.y * max_speed

	# Smooth acceleration & deceleration independently on X and Z
	if input_vector.x != 0.0:
		velocity.x = move_toward(velocity.x, target_velocity_x, acceleration * delta)
	else:
		velocity.x = move_toward(velocity.x, 0.0, deceleration * delta)

	if input_vector.y != 0.0:
		velocity.z = move_toward(velocity.z, target_velocity_z, acceleration * delta)
	else:
		velocity.z = move_toward(velocity.z, 0.0, deceleration * delta)

	# Execute physics movement
	move_and_slide()

	# CRITICAL: Helicopter's nose MUST always face forward (-Z / top of screen).
	# Yaw rotation is strictly locked to 0.0 degrees at all times.
	rotation.y = 0.0

	# Prevent movement outside the battlefield boundaries
	global_position.x = clampf(global_position.x, min_x, max_x)
	global_position.z = clampf(global_position.z, min_z, max_z)

## Bank body sideways when moving left/right without rotating the nose
func _apply_banking_and_tilt(delta: float) -> void:
	if helicopter_body_mesh == null:
		return

	# Roll banking: roll body left when strafing left, roll right when strafing right
	var target_roll: float = -input_vector.x * deg_to_rad(roll_bank_angle_max_deg)
	current_roll = lerp_angle(current_roll, target_roll, roll_speed * delta)

	# Pitch tilt: nose pitches slightly down when accelerating forward
	var target_pitch: float = input_vector.y * deg_to_rad(pitch_tilt_max_deg)
	current_pitch = lerp_angle(current_pitch, target_pitch, roll_speed * delta)

	helicopter_body_mesh.rotation.z = current_roll
	helicopter_body_mesh.rotation.x = current_pitch
	helicopter_body_mesh.rotation.y = 0.0 # Strictly keep nose forward

## High-speed rotor blade visual animation
func _spin_rotors(delta: float) -> void:
	if main_rotor_mesh:
		main_rotor_mesh.rotate_y(38.0 * delta)
	if tail_rotor_mesh:
		tail_rotor_mesh.rotate_x(45.0 * delta)

## Automatic twin machine gun & missile cooldown handling
func _process_weapons(delta: float) -> void:
	# Machine gun auto fire
	mg_timer += delta
	if auto_fire_enabled and mg_timer >= machine_gun_fire_rate:
		mg_timer = 0.0
		_fire_twin_machine_guns()

	# Missile cooldown timer
	if missile_timer > 0.0:
		missile_timer -= delta
		var progress: float = 1.0 - (missile_timer / missile_cooldown)
		emit_signal("missile_ready", false, progress)
	else:
		emit_signal("missile_ready", true, 1.0)

	# Dedicated missile attack trigger
	if Input.is_action_just_pressed("fire_missile"):
		launch_missiles()

func _fire_twin_machine_guns() -> void:
	if bullet_scene == null:
		return

	if left_gun_muzzle:
		_spawn_bullet(left_gun_muzzle.global_position)
	if right_gun_muzzle:
		_spawn_bullet(right_gun_muzzle.global_position)

func _spawn_bullet(spawn_pos: Vector3) -> void:
	var bullet = bullet_scene.instantiate()
	get_parent().add_child(bullet)
	bullet.global_position = spawn_pos
	bullet.global_rotation = Vector3.ZERO # Forward towards -Z

## Dedicated missile salvo launch
func launch_missiles() -> void:
	if missile_timer > 0.0 or missile_scene == null:
		return

	missile_timer = missile_cooldown

	# Launch dual missiles from left & right pylons
	if left_missile_pod:
		_spawn_missile(left_missile_pod.global_position)
	if right_missile_pod:
		_spawn_missile(right_missile_pod.global_position)

func _spawn_missile(spawn_pos: Vector3) -> void:
	var missile = missile_scene.instantiate()
	get_parent().add_child(missile)
	missile.global_position = spawn_pos
	if missile.has_method("set_target"):
		missile.set_target(target_lock_node)

## Damage, Armor & Shield system
func take_damage(amount: float) -> void:
	if current_shield > 0.0:
		var shield_damage = minf(current_shield, amount)
		current_shield -= shield_damage
		amount -= shield_damage
		emit_signal("shield_changed", current_shield, max_shield)

	if amount > 0.0 and current_armor > 0.0:
		var armor_damage = minf(current_armor, amount)
		current_armor -= armor_damage
		amount -= armor_damage
		emit_signal("armor_changed", current_armor, max_armor)

	if amount > 0.0:
		current_health = maxf(0.0, current_health - amount)
		emit_signal("health_changed", current_health, max_health)

	if current_health <= 0.0:
		emit_signal("player_destroyed")
		# Instantiate explosion effect and disable controls
		queue_free()
