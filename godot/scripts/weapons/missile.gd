class_name Missile
extends Area3D

@export var initial_speed: float = 18.0
@export var max_speed: float = 52.0
@export var acceleration: float = 40.0
@export var turn_speed: float = 4.5
@export var damage: float = 85.0
@export var explosion_radius: float = 6.0
@export var lifetime: float = 4.0

var current_speed: float = 18.0
var target: Node3D = null
var elapsed: float = 0.0

func _ready() -> void:
	current_speed = initial_speed
	body_entered.connect(_on_impact)
	area_entered.connect(_on_area_impact)

func set_target(new_target: Node3D) -> void:
	target = new_target

func _physics_process(delta: float) -> void:
	elapsed += delta
	if elapsed >= lifetime:
		_explode()
		return

	# Accelerate forward
	current_speed = move_toward(current_speed, max_speed, acceleration * delta)

	# Homing steering towards locked target or fly straight -Z
	if is_instance_valid(target):
		var target_dir = (target.global_position - global_position).normalized()
		var forward = -transform.basis.z
		var new_forward = forward.lerp(target_dir, turn_speed * delta).normalized()
		look_at(global_position + new_forward, Vector3.UP)

	global_position += -transform.basis.z * current_speed * delta

func _on_impact(body: Node3D) -> void:
	if body == self or body is PlayerHelicopter or body.is_in_group("player"):
		return
	if body.has_method("take_damage"):
		body.take_damage(damage)
	_explode(body)

func _on_area_impact(area: Area3D) -> void:
	if area == self or area is PlayerHelicopter or area.is_in_group("player") or area.is_in_group("player_projectiles"):
		return
	if area.has_method("take_damage"):
		area.take_damage(damage)
	_explode(area)

func _explode(direct_hit: Node = null) -> void:
	if get_tree() != null:
		for enemy in get_tree().get_nodes_in_group("enemies"):
			if enemy == direct_hit or not is_instance_valid(enemy) or not (enemy is Node3D):
				continue
			if global_position.distance_to(enemy.global_position) <= explosion_radius and enemy.has_method("take_damage"):
				enemy.take_damage(damage * 0.5)
	queue_free()
