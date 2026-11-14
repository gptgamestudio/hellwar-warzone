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
	if body.has_method("take_damage"):
		body.take_damage(damage)
	_explode()

func _on_area_impact(area: Area3D) -> void:
	if area.has_method("take_damage"):
		area.take_damage(damage)
	_explode()

func _explode() -> void:
	# Area damage check
	queue_free()
