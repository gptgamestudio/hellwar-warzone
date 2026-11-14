class_name BossBase
extends Area3D

signal boss_health_changed(current: float, max_val: float, phase: int)
signal boss_phase_transition(new_phase: int, phase_name: String)
signal boss_defeated(score_reward: int)

enum BossType { DRAGON, ALIEN, ORC, HUMAN }

@export var boss_type: BossType = BossType.HUMAN
@export var boss_name: String = "Project Titan: Fortress Gunship"
@export var max_health: float = 600.0
@export var score_reward: int = 15000

var current_health: float = 600.0
var current_phase: int = 1
var total_phases: int = 3
var is_invulnerable: bool = false
var target_player: Node3D = null

func _ready() -> void:
	current_health = max_health
	var players = get_tree().get_nodes_in_group("player")
	if players.size() > 0:
		target_player = players[0]
	emit_signal("boss_health_changed", current_health, max_health, current_phase)

func take_damage(amount: float) -> void:
	if is_invulnerable:
		return

	current_health -= amount
	emit_signal("boss_health_changed", current_health, max_health, current_phase)

	# Phase checking
	var hp_pct = current_health / max_health
	if hp_pct <= 0.33 and current_phase < 3:
		_transition_to_phase(3)
	elif hp_pct <= 0.66 and current_phase < 2:
		_transition_to_phase(2)

	if current_health <= 0.0:
		_trigger_destruction_sequence()

func _transition_to_phase(new_phase: int) -> void:
	current_phase = new_phase
	var p_name = "PHASE %d: OVERDRIVE" % new_phase
	emit_signal("boss_phase_transition", new_phase, p_name)

func _trigger_destruction_sequence() -> void:
	is_invulnerable = true
	emit_signal("boss_defeated", score_reward)
	queue_free()
