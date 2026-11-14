class_name CameraFollow
extends Camera3D

@export var target: Node3D
@export var smooth_speed: float = 6.0
@export var camera_offset: Vector3 = Vector3(0.0, 32.0, 16.0)
@export var forward_lead: float = 3.0
@export var pitch_angle_deg: float = -62.0

func _ready() -> void:
	rotation_degrees.x = pitch_angle_deg
	rotation_degrees.y = 0.0
	rotation_degrees.z = 0.0

func _physics_process(delta: float) -> void:
	if not is_instance_valid(target):
		return

	# Lead camera slightly forward along Z and follow X
	var target_pos = target.global_position + camera_offset
	target_pos.z -= forward_lead

	global_position.x = lerpf(global_position.x, target_pos.x * 0.45, smooth_speed * delta)
	global_position.y = lerpf(global_position.y, target_pos.y, smooth_speed * delta)
	global_position.z = lerpf(global_position.z, target_pos.z, smooth_speed * delta)
