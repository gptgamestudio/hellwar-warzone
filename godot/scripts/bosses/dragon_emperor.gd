class_name DragonEmperor
extends BossBase

signal weak_point_hit(damage: float)

@export var fire_breath_active: bool = false
@export var swooping_charge: bool = false

var breath_timer: float = 0.0
var salvo_timer: float = 0.0
var charge_timer: float = 0.0
var flight_wobble: float = 0.0

@onready var weak_point: Area3D = get_node_or_null("DragonHeartWeakPoint")

func _ready() -> void:
	boss_type = BossType.DRAGON
	boss_name = "Ignis Rex: Dragon Emperor"
	max_health = 1800.0
	score_reward = 25000
	super._ready()

func _physics_process(delta: float) -> void:
	flight_wobble += delta * 2.0
	position.x = sin(flight_wobble * 0.7) * 4.5
	
	breath_timer += delta
	salvo_timer += delta
	charge_timer += delta

	# Phase 1: Fireball salvos & flight patrol
	if current_phase == 1:
		if salvo_timer > 3.0:
			_cast_fireball_salvo(3)
			salvo_timer = 0.0
			
	# Phase 2: Wing Shockwaves & Fire breath streams
	elif current_phase == 2:
		if salvo_timer > 2.2:
			_cast_fireball_salvo(5)
			salvo_timer = 0.0
		if breath_timer > 5.0:
			_activate_fire_breath()
			breath_timer = 0.0

	# Phase 3: Enraged Aerial Charging Dive & Volcanic Inferno
	elif current_phase == 3:
		if charge_timer > 6.0 and not swooping_charge:
			_execute_aerial_charge()
			charge_timer = 0.0
		if salvo_timer > 1.8:
			_cast_fireball_salvo(7)
			salvo_timer = 0.0

func _cast_fireball_salvo(count: int) -> void:
	for i in range(count):
		var spread_angle = (i - count / 2.0) * 0.25
		# Fireball projectile spawn logic

func _activate_fire_breath() -> void:
	fire_breath_active = true
	# Continuous 60-degree cone flame emission

func _execute_aerial_charge() -> void:
	swooping_charge = true
	# Swoops toward player's Z coordinate then recovers altitude

func take_weak_point_damage(damage: float) -> void:
	var crit_damage = damage * 2.2
	emit_signal("weak_point_hit", crit_damage)
	take_damage(crit_damage)
