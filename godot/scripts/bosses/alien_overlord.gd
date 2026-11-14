class_name AlienOverlord
extends BossBase

signal subsystem_destroyed(subsystem_name: String)

@export var shield_active: bool = true
@export var shield_health: float = 800.0
@export var max_shield: float = 800.0

var port_cannon_destroyed: bool = false
var star_cannon_destroyed: bool = false

var plasma_timer: float = 0.0
var ufo_summon_timer: float = 0.0
var homing_torpedo_timer: float = 0.0
var mothership_rotation: float = 0.0

func _ready() -> void:
	boss_type = BossType.ALIEN
	boss_name = "Overseer Prime: Alien Mothership"
	max_health = 2200.0
	score_reward = 28000
	super._ready()

func _physics_process(delta: float) -> void:
	mothership_rotation += delta * 0.5
	rotation.y = mothership_rotation
	
	plasma_timer += delta
	ufo_summon_timer += delta
	homing_torpedo_timer += delta

	# Shield regeneration in phase 1 & 2 if not broken
	if shield_active and shield_health < max_shield:
		shield_health = min(max_shield, shield_health + delta * 25.0)

	# Phase 1: Heavy Dual Plasma pods & perimeter energy shield
	if current_phase == 1:
		if plasma_timer > 2.5:
			_fire_plasma_barrage()
			plasma_timer = 0.0

	# Phase 2: UFO escort fighter deployment & Homing plasma torpedoes
	elif current_phase == 2:
		if plasma_timer > 2.0:
			_fire_plasma_barrage()
			plasma_timer = 0.0
		if ufo_summon_timer > 7.0:
			_deploy_ufo_escorts(3)
			ufo_summon_timer = 0.0
		if homing_torpedo_timer > 4.5:
			_launch_homing_plasma()
			homing_torpedo_timer = 0.0

	# Phase 3: Shield Collapse & Overcharged Antimatter Sweeps
	elif current_phase == 3:
		shield_active = false
		if plasma_timer > 1.2:
			_fire_plasma_barrage()
			plasma_timer = 0.0
		if homing_torpedo_timer > 3.0:
			_launch_homing_plasma()
			homing_torpedo_timer = 0.0

func damage_subsystem(is_port: bool, amount: float) -> void:
	if is_port and not port_cannon_destroyed:
		port_cannon_destroyed = true
		emit_signal("subsystem_destroyed", "PORT PLASMA CANNON")
		take_damage(amount * 1.5)
	elif not is_port and not star_cannon_destroyed:
		star_cannon_destroyed = true
		emit_signal("subsystem_destroyed", "STARBOARD PLASMA CANNON")
		take_damage(amount * 1.5)

func _fire_plasma_barrage() -> void:
	# Heavy plasma projectile emission
	pass

func _deploy_ufo_escorts(count: int) -> void:
	# Spawn UFO interceptors
	pass

func _launch_homing_plasma() -> void:
	# Seeking plasma projectile emission
	pass
