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
	if body.has_method("take_damage") and not (body is PlayerHelicopter):
		body.take_damage(damage)
	_explode()

func _on_area_impact(area: Area3D) -> void:
	if area.has_method("take_damage") and not (area is PlayerHelicopter):
		area.take_damage(damage)
	_explode()

func _explode() -> void:
	# Area splash damage to surrounding enemies
	queue_free()
