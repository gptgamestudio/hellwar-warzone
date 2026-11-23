class_name CameraFollow
extends Camera3D

@export var target: Node3D
@export var smooth_speed: float = 6.5
@export var camera_offset: Vector3 = Vector3(0.0, 32.0, 16.0)
@export var forward_lead: float = 3.0
@export var pitch_angle_deg: float = -62.0
@export var shake_enabled: bool = true

var _shake_amount: float = 0.0
var _rng := RandomNumberGenerator.new()

func _ready() -> void:
	rotation_degrees.x = pitch_angle_deg
	rotation_degrees.y = 0.0
	rotation_degrees.z = 0.0

func trigger_shake(intensity: float) -> void:
	if shake_enabled:
		_shake_amount = maxf(_shake_amount, intensity)

func _physics_process(delta: float) -> void:
	if not is_instance_valid(target):
		return

	var target_pos := target.global_position + camera_offset
	target_pos.z -= forward_lead

	var shake_offset := Vector3.ZERO
	if shake_enabled and _shake_amount > 0.01:
		shake_offset.x = _rng.randf_range(-_shake_amount, _shake_amount)
		shake_offset.z = _rng.randf_range(-_shake_amount, _shake_amount)
		_shake_amount = move_toward(_shake_amount, 0.0, 6.0 * delta)
	else:
		_shake_amount = 0.0

	global_position.x = lerpf(global_position.x, target_pos.x * 0.7 + shake_offset.x, smooth_speed * delta)
	global_position.y = lerpf(global_position.y, target_pos.y, smooth_speed * delta)
	global_position.z = lerpf(global_position.z, target_pos.z + shake_offset.z, smooth_speed * delta)
