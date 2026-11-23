class_name MissionController
extends Node

signal sortie_barracks_destroyed(barracks_id: String, empire_index: int, credits_awarded: int, score_awarded: int, total_destroyed: int)
signal sortie_hq_defeated(empire_index: int, score: int, kills: int, credits_earned: int)
signal sortie_player_defeated(score: int, kills: int, barracks_destroyed: int)
signal sortie_checkpoint_changed(checkpoint: Dictionary)
signal sortie_pause_requested()
signal sortie_return_to_menu_requested()

const MAX_ACTIVE_ENEMIES: int = 18
const EMPIRE_NAMES := ["DRAGON", "ALIEN", "ORC", "HUMAN"]
const EMPIRE_STAGE_NUMBERS := [1, 2, 3, 4]
const EMPIRE_COLORS := [
	Color(1.0, 0.24, 0.0),
	Color(0.84, 0.0, 0.98),
	Color(0.76, 0.25, 0.05),
	Color(0.01, 0.52, 0.78)
]

@export var empire_index: int = 0
@export var boss_scene: PackedScene
@export var rocket_scene: PackedScene

var player: PlayerHelicopter = null
var hud: GameHUD = null
var camera_follow: CameraFollow = null
var environment_generator: EnvironmentGenerator = null

var barracks_nodes: Array[BarracksOutpost] = []
var headquarters_node: EmpireHeadquarters = null
var active_boss: BossBase = null

var barracks_destroyed_count: int = 0
var boss_spawned: bool = false
var is_victory: bool = false
var is_game_over: bool = false

var score: int = 0
var kills: int = 0
var credits_earned: int = 0

var _enemy_spawn_timer: float = 1.6
var _hq_guard_spawn_timer: float = 0.0
var _threat_timer: float = 0.0
var _checkpoint_timer: float = 0.0
var _rng := RandomNumberGenerator.new()

func setup_battlefield(
	p_battlefield_root: Node3D,
	p_empire_index: int,
	p_boss_scene: PackedScene,
	config: Dictionary
) -> void:
	empire_index = clampi(p_empire_index, 0, 3)
	boss_scene = p_boss_scene
	player = p_battlefield_root.get_node_or_null("PlayerHelicopter") as PlayerHelicopter
	hud = p_battlefield_root.get_node_or_null("GameHUD") as GameHUD
	camera_follow = p_battlefield_root.get_node_or_null("CameraFollow") as CameraFollow
	environment_generator = p_battlefield_root as EnvironmentGenerator

	# Remove any static pre-placed boss so the boss emerges strictly when HQ unlocks & Danger Zone triggers
	for child in p_battlefield_root.get_children():
		if child is BossBase:
			child.queue_free()

	if camera_follow != null:
		camera_follow.shake_enabled = bool(config.get("screen_shake_enabled", true))

	if player != null:
		player.apply_configuration_dict(config)
		player.auto_fire_enabled = bool(config.get("auto_fire_by_default", true))
		_bind_player_signals()

	if hud != null:
		_bind_hud_signals()

	_init_open_world_objectives(p_battlefield_root, config)

	var checkpoint: Variant = config.get("checkpoint", null)
	if checkpoint is Dictionary and not (checkpoint as Dictionary).is_empty():
		restore_checkpoint(checkpoint as Dictionary)
	else:
		_show_threat("STAGE %d: DESTROY 50 BARRACKS TO UNLOCK HQ" % EMPIRE_STAGE_NUMBERS[empire_index], 4.0)

	_sync_hud()

func _bind_player_signals() -> void:
	if player == null:
		return
	if not player.health_changed.is_connected(_on_player_health_changed):
		player.health_changed.connect(_on_player_health_changed)
	if not player.armor_changed.is_connected(_on_player_armor_changed):
		player.armor_changed.connect(_on_player_armor_changed)
	if not player.shield_changed.is_connected(_on_player_shield_changed):
		player.shield_changed.connect(_on_player_shield_changed)
	if not player.hearts_changed.is_connected(_on_player_hearts_changed):
		player.hearts_changed.connect(_on_player_hearts_changed)
	if not player.missile_state_changed.is_connected(_on_player_missile_state):
		player.missile_state_changed.connect(_on_player_missile_state)
	if not player.rocket_state_changed.is_connected(_on_player_rocket_state):
		player.rocket_state_changed.connect(_on_player_rocket_state)
	if not player.flare_state_changed.is_connected(_on_player_flare_state):
		player.flare_state_changed.connect(_on_player_flare_state)
	if not player.overdrive_state_changed.is_connected(_on_player_overdrive_state):
		player.overdrive_state_changed.connect(_on_player_overdrive_state)
	if not player.player_destroyed.is_connected(_on_player_destroyed):
		player.player_destroyed.connect(_on_player_destroyed)

func _bind_hud_signals() -> void:
	if hud == null:
		return
	if not hud.pause_pressed.is_connected(_on_hud_pause_pressed):
		hud.pause_pressed.connect(_on_hud_pause_pressed)
	if not hud.auto_fire_toggled.is_connected(_on_hud_autofire_toggled):
		hud.auto_fire_toggled.connect(_on_hud_autofire_toggled)
	if not hud.missile_fired.is_connected(_on_hud_missile_fired):
		hud.missile_fired.connect(_on_hud_missile_fired)
	if not hud.rocket_fired.is_connected(_on_hud_rocket_fired):
		hud.rocket_fired.connect(_on_hud_rocket_fired)
	if not hud.flare_fired.is_connected(_on_hud_flare_fired):
		hud.flare_fired.connect(_on_hud_flare_fired)
	if not hud.overdrive_fired.is_connected(_on_hud_overdrive_fired):
		hud.overdrive_fired.connect(_on_hud_overdrive_fired)
	if not hud.move_input_changed.is_connected(_on_hud_move_input):
		hud.move_input_changed.connect(_on_hud_move_input)

func _init_open_world_objectives(parent_node: Node3D, config: Dictionary) -> void:
	barracks_nodes.clear()
	barracks_destroyed_count = 0

	var destroyed_set := {}
	var raw_ids: Variant = config.get("destroyed_barracks_ids", [])
	if raw_ids is Array:
		for bid in raw_ids:
			destroyed_set[str(bid)] = true

	var empire_name := EMPIRE_NAMES[empire_index]
	var empire_color := EMPIRE_COLORS[empire_index]
	_rng.seed = empire_index * 1000 + 42

	var objectives_root := Node3D.new()
	objectives_root.name = "ObjectivesRoot"
	parent_node.add_child(objectives_root)

	for i in range(1, 51):
		var pos_x := _rng.randf_range(-170.0, 170.0)
		var pos_z := -(35.0 + float(i) * 18.0 + _rng.randf_range(-4.0, 4.0))
		var b_id := "BARRACKS_%s_%d" % [empire_name, i]
		var already := destroyed_set.has(b_id)

		var outpost := BarracksOutpost.new()
		outpost.name = b_id
		outpost.position = Vector3(pos_x, 0.1, pos_z)
		var defs: Array[int] = [0, 2]
		outpost.configure(b_id, i, empire_index, empire_color, already, defs)
		outpost.barracks_destroyed.connect(_on_barracks_destroyed)
		objectives_root.add_child(outpost)
		barracks_nodes.append(outpost)
		if already:
			barracks_destroyed_count += 1

	var hq_unlocked := barracks_destroyed_count >= 50 or bool(config.get("hq_unlocked", false))
	var hq_destroyed := bool(config.get("hq_destroyed", false))
	headquarters_node = EmpireHeadquarters.new()
	headquarters_node.name = "HQ_%s" % empire_name
	headquarters_node.position = Vector3(0.0, 0.1, -1020.0)
	headquarters_node.configure(
		"HQ_%s" % empire_name,
		empire_index,
		empire_color,
		hq_unlocked,
		hq_destroyed,
		hq_destroyed,
		1000
	)
	headquarters_node.set_barracks_cleared(barracks_destroyed_count)
	headquarters_node.hq_locked_hit.connect(_on_hq_locked_hit)
	headquarters_node.hq_danger_zone_entered.connect(_on_hq_danger_zone)
	headquarters_node.hq_damaged.connect(_on_hq_damaged)
	headquarters_node.hq_destroyed.connect(_on_hq_destroyed)
	objectives_root.add_child(headquarters_node)

func _physics_process(delta: float) -> void:
	if is_victory or is_game_over or player == null:
		return

	if _threat_timer > 0.0:
		_threat_timer = maxf(0.0, _threat_timer - delta)
		if _threat_timer == 0.0 and hud != null:
			hud.hide_threat_warning()

	_update_hq_proximity()
	_update_spawner(delta)

	_checkpoint_timer += delta
	if _checkpoint_timer >= 5.0:
		_checkpoint_timer = 0.0
		emit_signal("sortie_checkpoint_changed", export_checkpoint())

func _update_hq_proximity() -> void:
	if headquarters_node == null or headquarters_node.is_destroyed or player == null:
		return
	var dist := Vector2(player.global_position.x, player.global_position.z).distance_to(
		Vector2(headquarters_node.global_position.x, headquarters_node.global_position.z)
	)
	var in_zone := dist <= headquarters_node.danger_zone_radius or player.global_position.z <= headquarters_node.global_position.z + headquarters_node.danger_zone_radius
	if in_zone:
		headquarters_node.trigger_danger_zone()
		if headquarters_node.is_unlocked and not headquarters_node.is_boss_defeated and not boss_spawned and active_boss == null:
			_spawn_boss()

func _update_spawner(delta: float) -> void:
	_enemy_spawn_timer -= delta
	if _enemy_spawn_timer > 0.0:
		return
	_enemy_spawn_timer = maxf(0.85, 2.2 - (float(barracks_destroyed_count) / 50.0) * 1.1)

	var active_enemies := 0
	if get_tree() != null:
		for n in get_tree().get_nodes_in_group("enemy_units"):
			if is_instance_valid(n):
				active_enemies += 1
	if active_enemies >= MAX_ACTIVE_ENEMIES or player == null:
		return

	var enemy := EnemyTarget.new()
	var col := CollisionShape3D.new()
	var shape := BoxShape3D.new()
	shape.size = Vector3(3.0, 1.5, 5.0)
	col.shape = shape
	enemy.add_child(col)

	var visual := MeshInstance3D.new()
	var mesh := BoxMesh.new()
	mesh.size = Vector3(3.0, 1.4, 5.0)
	visual.mesh = mesh
	var mat := StandardMaterial3D.new()
	mat.albedo_color = EMPIRE_COLORS[empire_index]
	visual.material_override = mat
	enemy.add_child(visual)

	enemy.add_to_group("enemies")
	enemy.add_to_group("enemy_units")
	enemy.global_position = Vector3(
		clampf(player.global_position.x + _rng.randf_range(-14.0, 14.0), -180.0, 180.0),
		3.0,
		player.global_position.z - 42.0
	)
	enemy.destroyed.connect(_on_enemy_destroyed)
	get_parent().add_child(enemy)

func _spawn_boss() -> void:
	if boss_scene == null or boss_spawned:
		return
	boss_spawned = true
	var boss_inst := boss_scene.instantiate()
	if boss_inst is BossBase:
		active_boss = boss_inst as BossBase
		active_boss.global_position = Vector3(0.0, 6.0, headquarters_node.global_position.z + 24.0)
		active_boss.boss_health_changed.connect(_on_boss_health_changed)
		active_boss.boss_defeated.connect(_on_boss_defeated)
		get_parent().add_child(active_boss)
		_show_threat("BOSS ALERT: %s EMERGED!" % active_boss.boss_name.to_upper(), 4.5)
	elif boss_inst != null:
		boss_inst.queue_free()

func _on_barracks_destroyed(b_id: String, emp_idx: int, cred: int, pts: int) -> void:
	barracks_destroyed_count = 0
	for b in barracks_nodes:
		if b.is_destroyed:
			barracks_destroyed_count += 1
	score += pts
	credits_earned += cred
	kills += 1
	if player != null:
		player.add_overdrive_charge(8.0)
	if headquarters_node != null:
		headquarters_node.set_barracks_cleared(barracks_destroyed_count)
	if barracks_destroyed_count >= 50:
		_show_threat("ALL 50 BARRACKS CLEARED! ADVANCE TO HQ!", 4.5)
	emit_signal("sortie_barracks_destroyed", b_id, emp_idx, cred, pts, barracks_destroyed_count)
	emit_signal("sortie_checkpoint_changed", export_checkpoint())
	_sync_hud()

func _on_hq_locked_hit(cleared: int) -> void:
	_show_threat("HQ SHIELD LOCKED! DESTROY ALL 50 BARRACKS (%d/50)" % cleared, 2.5)

func _on_hq_danger_zone() -> void:
	if headquarters_node != null and not headquarters_node.is_unlocked:
		_show_threat("WARNING: HQ DANGER ZONE! CLEAR %d/50 BARRACKS" % barracks_destroyed_count, 3.5)

func _on_hq_damaged(_hits_left: int, _max_hits: int) -> void:
	_sync_hud()

func _on_hq_destroyed(_hq_id: String, emp_idx: int) -> void:
	if is_victory:
		return
	is_victory = true
	score += 5000
	credits_earned += 2500
	_sync_hud()
	emit_signal("sortie_hq_defeated", emp_idx, score, kills, credits_earned)

func _on_boss_health_changed(current: float, maximum: float, phase: int) -> void:
	if hud != null:
		hud.update_boss_health(active_boss.boss_name if active_boss else "BOSS", current, maximum, phase)

func _on_boss_defeated(score_reward: int) -> void:
	score += score_reward
	credits_earned += 1500
	active_boss = null
	if headquarters_node != null:
		headquarters_node.mark_boss_defeated()
	if hud != null:
		hud.hide_boss_health()
	_show_threat("BOSS DESTROYED! STRIKE THE EXPOSED HEADQUARTERS!", 4.5)
	emit_signal("sortie_checkpoint_changed", export_checkpoint())
	_sync_hud()

func _on_enemy_destroyed(pts: int) -> void:
	score += pts
	kills += 1
	credits_earned += 25
	if player != null:
		player.add_overdrive_charge(4.0)
	_sync_hud()

func _on_player_destroyed() -> void:
	if is_game_over or is_victory:
		return
	is_game_over = true
	_sync_hud()
	emit_signal("sortie_player_defeated", score, kills, barracks_destroyed_count)

func continue_after_ad_reward() -> void:
	if player == null:
		return
	player.restore_all_hearts()
	is_game_over = false
	_show_threat("AD CONTINUE: +5 HEARTS RESTORED! PRESS ATTACK!", 3.8)
	_sync_hud()

func export_checkpoint() -> Dictionary:
	var destroyed_ids: Array[String] = []
	for b in barracks_nodes:
		if b.is_destroyed:
			destroyed_ids.append(b.barracks_id)
	var px := player.global_position.x if player != null else 0.0
	var pz := -player.global_position.z if player != null else 0.0
	return {
		"empire": EMPIRE_NAMES[empire_index],
		"stageNumber": EMPIRE_STAGE_NUMBERS[empire_index],
		"playerX": px,
		"playerY": pz,
		"hearts": player.hearts if player != null else 5,
		"hitsRemainingInHeart": player.hits_remaining_in_heart if player != null else 10,
		"score": score,
		"kills": kills,
		"creditsEarned": credits_earned,
		"destroyedBarracksIds": destroyed_ids,
		"bossSpawned": boss_spawned,
		"bossDefeated": headquarters_node.is_boss_defeated if headquarters_node != null else false,
		"bossHitsRemaining": int(active_boss.current_health) if active_boss != null else 0,
		"hqUnlocked": headquarters_node.is_unlocked if headquarters_node != null else false,
		"hqHitsRemaining": headquarters_node.hits_remaining if headquarters_node != null else 1000,
		"overdriveCharge": player.overdrive_charge if player != null else 40.0
	}

func restore_checkpoint(cp: Dictionary) -> void:
	if player != null:
		player.global_position = Vector3(
			clampf(float(cp.get("playerX", 0.0)), -240.0, 240.0),
			3.0,
			clampf(-float(cp.get("playerY", 0.0)), -1080.0, 20.0)
		)
		player.hearts = clampi(int(cp.get("hearts", 5)), 1, player.max_hearts)
		player.hits_remaining_in_heart = clampi(int(cp.get("hitsRemainingInHeart", 10)), 1, player.hits_per_heart)
		player.overdrive_charge = clampf(float(cp.get("overdriveCharge", 40.0)), 0.0, 100.0)
	score = int(cp.get("score", 0))
	kills = int(cp.get("kills", 0))
	credits_earned = int(cp.get("creditsEarned", 0))

func _show_threat(msg: String, duration: float) -> void:
	_threat_timer = duration
	if hud != null:
		hud.show_threat_warning(msg)

func _sync_hud() -> void:
	if hud == null:
		return
	hud.update_score(score)
	hud.update_kills(kills)
	hud.update_mission_progress((float(barracks_destroyed_count) / 50.0) * 100.0)
	var hq_unlocked := headquarters_node.is_unlocked if headquarters_node != null else false
	var boss_defeated := headquarters_node.is_boss_defeated if headquarters_node != null else false
	var hq_hits := headquarters_node.hits_remaining if headquarters_node != null else 1000
	var hq_max := headquarters_node.max_hits if headquarters_node != null else 1000
	hud.update_objective_status(barracks_destroyed_count, 50, hq_unlocked, active_boss != null, boss_defeated, hq_hits, hq_max)

func _on_player_health_changed(cur: float, mx: float) -> void:
	if hud != null:
		hud.update_health(cur, mx)

func _on_player_armor_changed(cur: float, mx: float) -> void:
	if hud != null:
		hud.update_armor(cur, mx)

func _on_player_shield_changed(cur: float, mx: float) -> void:
	if hud != null:
		hud.update_shield(cur, mx)

func _on_player_hearts_changed(hearts: int, max_hearts: int, hits_left: int, hits_per: int) -> void:
	if hud != null:
		hud.update_hearts(hearts, max_hearts, hits_left, hits_per)

func _on_player_missile_state(is_ready: bool, pct: float, ammo: int, max_ammo: int) -> void:
	if hud != null:
		hud.set_missile_cooldown(pct, is_ready, ammo, max_ammo)

func _on_player_rocket_state(is_ready: bool, pct: float, ammo: int, max_ammo: int) -> void:
	if hud != null:
		hud.set_rocket_state(pct, is_ready, ammo, max_ammo)

func _on_player_flare_state(is_ready: bool, pct: float) -> void:
	if hud != null:
		hud.set_flare_state(pct, is_ready)

func _on_player_overdrive_state(is_ready: bool, pct: float) -> void:
	if hud != null:
		hud.set_overdrive_state(pct, is_ready)

func _on_hud_pause_pressed() -> void:
	emit_signal("sortie_pause_requested")

func _on_hud_autofire_toggled(enabled: bool) -> void:
	if player != null:
		player.auto_fire_enabled = enabled

func _on_hud_missile_fired() -> void:
	if player != null:
		player.launch_missiles()

func _on_hud_rocket_fired() -> void:
	if player != null:
		player.launch_rockets()

func _on_hud_flare_fired() -> void:
	if player != null:
		player.activate_flare()

func _on_hud_overdrive_fired() -> void:
	if player != null:
		player.activate_overdrive()

func _on_hud_move_input(vec: Vector2) -> void:
	if player != null:
		player.set_external_move_input(vec)
