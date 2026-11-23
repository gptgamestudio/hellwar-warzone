class_name Rocket
extends Area3D

@export var initial_speed: float = 24.0
@export var max_speed: float = 68.0
@export var acceleration: float = 60.0
@export var damage: float = 45.0
@export var splash_radius: float = 4.5
@export var lifetime: float = 3.0

var current_speed: float = 24.0
var elapsed: float = 0.0
var spread_dir: Vector3 = Vector3.ZERO

func _ready() -> void:
	current_speed = initial_speed
	body_entered.connect(_on_impact)
	area_entered.connect(_on_area_impact)

func set_spread_direction(dir: Vector3) -> void:
	spread_dir = dir

func _physics_process(delta: float) -> void:
	elapsed += delta
	if elapsed >= lifetime:
		_explode()
		return

	current_speed = move_toward(current_speed, max_speed, acceleration * delta)
	# Fly forward along -Z with slight spread
	var move_vec = (-transform.basis.z + spread_dir * 0.15).normalized()
	global_position += move_vec * current_speed * delta

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
			if global_position.distance_to(enemy.global_position) <= splash_radius and enemy.has_method("take_damage"):
				enemy.take_damage(damage * 0.5)
	queue_free()
