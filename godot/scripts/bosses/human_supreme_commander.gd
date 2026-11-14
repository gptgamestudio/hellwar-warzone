class_name HumanSupremeCommander
extends BossBase

signal subsystem_destroyed(subsystem_name: String)

@export var shield_active: bool = true
@export var shield_health: float = 600.0

var vulcan_destroyed: bool = false
var port_missile_destroyed: bool = false
var star_missile_destroyed: bool = false

var gatling_timer: float = 0.0
var missile_timer: float = 0.0
var airstrike_timer: float = 0.0
var rotor_speed: float = 0.0

func _ready() -> void:
	boss_type = BossType.HUMAN
	boss_name = "Project Titan: Supreme Gunship"
	max_health = 2600.0
	score_reward = 30000
	super._ready()

func _physics_process(delta: float) -> void:
	rotor_speed += delta * 30.0
	gatling_timer += delta
	missile_timer += delta
	airstrike_timer += delta

	# Dynamic lateral combat maneuvers
	position.x = sin(Time.get_ticks_msec() / 1200.0) * 5.0

	# Phase 1: 30mm Rotary Vulcan and dual Stinger missile pods
	if current_phase == 1:
		if gatling_timer > 2.0 and not vulcan_destroyed:
			_fire_rotary_vulcan()
			gatling_timer = 0.0
		if missile_timer > 4.0:
			_fire_guided_missiles()
			missile_timer = 0.0

	# Phase 2: Su-57 Stealth Fighter airstrikes and rapid missile volleys
	elif current_phase == 2:
		if gatling_timer > 1.5 and not vulcan_destroyed:
			_fire_rotary_vulcan()
			gatling_timer = 0.0
		if missile_timer > 3.0:
			_fire_guided_missiles()
			missile_timer = 0.0
		if airstrike_timer > 7.0:
			_call_fighter_airstrike()
			airstrike_timer = 0.0

	# Phase 3: Titan Overdrive - Max speed, continuous kinetic shield & combined barrage
	elif current_phase == 3:
		if gatling_timer > 1.0 and not vulcan_destroyed:
			_fire_rotary_vulcan()
			gatling_timer = 0.0
		if missile_timer > 2.2:
			_fire_guided_missiles()
			missile_timer = 0.0
		if airstrike_timer > 5.0:
			_call_fighter_airstrike()
			airstrike_timer = 0.0

func damage_subsystem(subsystem_name: String, amount: float) -> void:
	if subsystem_name == "VULCAN" and not vulcan_destroyed:
		vulcan_destroyed = true
		emit_signal("subsystem_destroyed", "30MM ROTARY VULCAN")
		take_damage(amount * 1.5)
	elif subsystem_name == "PORT_POD" and not port_missile_destroyed:
		port_missile_destroyed = true
		emit_signal("subsystem_destroyed", "PORT STINGER POD")
		take_damage(amount * 1.5)
	elif subsystem_name == "STAR_POD" and not star_missile_destroyed:
		star_missile_destroyed = true
		emit_signal("subsystem_destroyed", "STARBOARD STINGER POD")
		take_damage(amount * 1.5)

func _fire_rotary_vulcan() -> void:
	pass

func _fire_guided_missiles() -> void:
	pass

func _call_fighter_airstrike() -> void:
	pass
