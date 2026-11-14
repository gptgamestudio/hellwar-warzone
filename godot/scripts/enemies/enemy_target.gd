class_name EnemyTarget
extends Area3D

signal destroyed(score_value: int)

enum EmpireType { DRAGON, ALIEN, ORC, HUMAN }

@export var empire: EmpireType = EmpireType.HUMAN
@export var max_health: float = 60.0
@export var speed: float = 8.0
@export var strafe_amplitude: float = 4.0
@export var strafe_frequency: float = 1.8
@export var score_points: int = 250

var current_health: float = 60.0
var base_x: float = 0.0
var time_alive: float = 0.0

func _ready() -> void:
	current_health = max_health
	base_x = global_position.x

func _physics_process(delta: float) -> void:
	time_alive += delta
	# Move downward towards player (+Z) while strafing horizontally
	global_position.z += speed * delta
	global_position.x = base_x + sin(time_alive * strafe_frequency) * strafe_amplitude

	# Remove if passed battlefield lower bounds
	if global_position.z > 25.0:
		queue_free()

func take_damage(amount: float) -> void:
	current_health -= amount
	if current_health <= 0.0:
		emit_signal("destroyed", score_points)
		queue_free()
