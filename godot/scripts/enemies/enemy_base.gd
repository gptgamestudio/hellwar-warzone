class_name EnemyBase
extends Area3D

signal destroyed(score_val: int)

enum EnemyCategory { ATTACK_HELICOPTER, FIGHTER_AIRCRAFT, GROUND_TANK, AA_TURRET, MISSILE_LAUNCHER }

@export var category: EnemyCategory = EnemyCategory.ATTACK_HELICOPTER
@export var max_health: float = 75.0
@export var speed: float = 8.0
@export var score_value: int = 400
@export var detection_range: float = 35.0

var current_health: float = 75.0
var target_player: Node3D = null
var is_active: bool = true

func _ready() -> void:
	current_health = max_health
	# Find player node in group
	var players = get_tree().get_nodes_in_group("player")
	if players.size() > 0:
		target_player = players[0]

func take_damage(amount: float) -> void:
	if not is_active:
		return
	current_health -= amount
	if current_health <= 0.0:
		die()

func die() -> void:
	is_active = false
	emit_signal("destroyed", score_value)
	# Trigger particle explosion
	queue_free()
