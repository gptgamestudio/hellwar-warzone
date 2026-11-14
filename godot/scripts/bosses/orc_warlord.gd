class_name OrcWarlord
extends BossBase

signal boiler_weakpoint_hit(damage: float)

@export var armor_reduction_pct: float = 0.40 # 40% damage resistance on armor plates
var siege_timer: float = 0.0
var flak_timer: float = 0.0
var scrap_summon_timer: float = 0.0
var ram_charge_timer: float = 0.0
var is_ramming: bool = false

func _ready() -> void:
	boss_type = BossType.ORC
	boss_name = "Warlord Gashrak: Ironclad Sky Fortress"
	max_health = 2400.0
	score_reward = 26000
	super._ready()

func _physics_process(delta: float) -> void:
	siege_timer += delta
	flak_timer += delta
	scrap_summon_timer += delta
	ram_charge_timer += delta

	# Heavy zeppelin sway
	position.x = sin(Time.get_ticks_msec() / 1500.0) * 3.5

	# Phase 1: Heavy twin siege cannon volleys
	if current_phase == 1:
		if siege_timer > 3.0:
			_fire_siege_cannons()
			siege_timer = 0.0

	# Phase 2: Flak minefields and scrap copter wave deployment
	elif current_phase == 2:
		if siege_timer > 2.5:
			_fire_siege_cannons()
			siege_timer = 0.0
		if flak_timer > 4.0:
			_deploy_flak_minefield()
			flak_timer = 0.0
		if scrap_summon_timer > 8.0:
			_summon_scrap_reinforcements(2)
			scrap_summon_timer = 0.0

	# Phase 3: Iron Ramming Surge & Desperation Barrage
	elif current_phase == 3:
		if ram_charge_timer > 7.0 and not is_ramming:
			_execute_iron_ram_surge()
			ram_charge_timer = 0.0
		if siege_timer > 1.6:
			_fire_siege_cannons()
			siege_timer = 0.0

func hit_rear_boiler(damage: float) -> void:
	# Rear exposed boiler bypasses armor and takes 2.5x critical damage
	var crit = damage * 2.5
	emit_signal("boiler_weakpoint_hit", crit)
	take_damage(crit)

func take_damage(amount: float) -> void:
	# Normal hull hits apply armor reduction
	super.take_damage(amount * (1.0 - armor_reduction_pct))

func _fire_siege_cannons() -> void:
	pass

func _deploy_flak_minefield() -> void:
	pass

func _summon_scrap_reinforcements(count: int) -> void:
	pass

func _execute_iron_ram_surge() -> void:
	pass
