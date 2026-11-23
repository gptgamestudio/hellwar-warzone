class_name BattleTheaterDirector
extends Node3D

## Persistent Option A Root Director hosted inside Kotlin's single GodotFragment.
## Starts in standby and loads/unloads Empire battlefield sorties cleanly without
## destroying or recreating the Godot engine instance.

const BATTLEFIELD_SCENES := {
	"DRAGON": "res://scenes/DragonBattlefield.tscn",
	"ALIEN": "res://scenes/AlienBattlefield.tscn",
	"ORC": "res://scenes/OrcBattlefield.tscn",
	"HUMAN": "res://scenes/HumanBattlefield.tscn"
}

const BOSS_SCENES := {
	"DRAGON": "res://scenes/BossDragon.tscn",
	"ALIEN": "res://scenes/BossAlien.tscn",
	"ORC": "res://scenes/BossOrc.tscn",
	"HUMAN": "res://scenes/BossHuman.tscn"
}

const EMPIRE_INDEX := {
	"DRAGON": 0,
	"ALIEN": 1,
	"ORC": 2,
	"HUMAN": 3
}

@onready var theater_container: Node3D = $ActiveTheaterContainer
@onready var standby_overlay: CanvasLayer = $StandbyOverlay

var active_battlefield: Node3D = null
var active_mission: MissionController = null
var last_sortie_config: Dictionary = {}
var is_in_standby: bool = true
var bridge_singleton: Object = null

func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	if Engine.has_singleton("HellwarBridge"):
		bridge_singleton = Engine.get_singleton("HellwarBridge")
		_connect_bridge_signals()
	enter_standby()

func _connect_bridge_signals() -> void:
	if bridge_singleton == null:
		return
	if bridge_singleton.has_signal("start_sortie_requested"):
		bridge_singleton.connect("start_sortie_requested", _on_bridge_start_sortie)
	if bridge_singleton.has_signal("restart_sortie_requested"):
		bridge_singleton.connect("restart_sortie_requested", restart_sortie)
	if bridge_singleton.has_signal("pause_state_requested"):
		bridge_singleton.connect("pause_state_requested", set_battle_paused)
	if bridge_singleton.has_signal("ad_continue_requested"):
		bridge_singleton.connect("ad_continue_requested", apply_ad_continue_reward)
	if bridge_singleton.has_signal("standby_requested"):
		bridge_singleton.connect("standby_requested", enter_standby)
	if bridge_singleton.has_signal("tactical_settings_updated"):
		bridge_singleton.connect("tactical_settings_updated", apply_tactical_settings)

func _on_bridge_start_sortie(config_json: String) -> void:
	var parsed: Variant = JSON.parse_string(config_json)
	if parsed is Dictionary:
		start_sortie(parsed as Dictionary)

func start_sortie(config: Dictionary) -> void:
	last_sortie_config = config.duplicate(true)
	_clear_active_theater()

	var empire_key := str(config.get("empire", "DRAGON")).to_upper()
	if not BATTLEFIELD_SCENES.has(empire_key):
		empire_key = "DRAGON"

	var scene_res := load(BATTLEFIELD_SCENES[empire_key])
	if not (scene_res is PackedScene):
		push_error("Failed to load battlefield scene for " + empire_key)
		enter_standby()
		return

	var battlefield := (scene_res as PackedScene).instantiate()
	if not (battlefield is Node3D):
		if battlefield != null:
			battlefield.queue_free()
		enter_standby()
		return

	active_battlefield = battlefield as Node3D
	active_battlefield.process_mode = Node.PROCESS_MODE_PAUSABLE
	theater_container.add_child(active_battlefield)

	var boss_res: PackedScene = null
	var raw_boss := load(BOSS_SCENES[empire_key])
	if raw_boss is PackedScene:
		boss_res = raw_boss as PackedScene

	active_mission = MissionController.new()
	active_mission.name = "MissionController"
	active_mission.process_mode = Node.PROCESS_MODE_PAUSABLE
	active_battlefield.add_child(active_mission)
	_connect_mission_events(active_mission)
	active_mission.setup_battlefield(
		active_battlefield,
		int(EMPIRE_INDEX.get(empire_key, 0)),
		boss_res,
		config
	)

	is_in_standby = false
	if standby_overlay:
		standby_overlay.visible = false
	get_tree().paused = false

func restart_sortie() -> void:
	if last_sortie_config.is_empty():
		return
	var fresh_config := last_sortie_config.duplicate(true)
	fresh_config.erase("checkpoint")
	start_sortie(fresh_config)

func set_battle_paused(paused: bool) -> void:
	if is_in_standby:
		return
	get_tree().paused = paused

func apply_ad_continue_reward() -> void:
	if active_mission != null:
		get_tree().paused = false
		active_mission.continue_after_ad_reward()

func apply_tactical_settings(settings_json: String) -> void:
	var parsed: Variant = JSON.parse_string(settings_json)
	if not (parsed is Dictionary) or active_mission == null:
		return
	var s := parsed as Dictionary
	if active_mission.camera_follow != null and s.has("screen_shake_enabled"):
		active_mission.camera_follow.shake_enabled = bool(s["screen_shake_enabled"])
	if active_mission.player != null and s.has("auto_fire_by_default"):
		active_mission.player.auto_fire_enabled = bool(s["auto_fire_by_default"])

func enter_standby() -> void:
	_clear_active_theater()
	is_in_standby = true
	if standby_overlay:
		standby_overlay.visible = true
	get_tree().paused = true

func export_current_checkpoint_json() -> String:
	if active_mission == null:
		return "{}"
	return JSON.stringify(active_mission.export_checkpoint())

func _clear_active_theater() -> void:
	active_mission = null
	if active_battlefield != null and is_instance_valid(active_battlefield):
		active_battlefield.queue_free()
		active_battlefield = null
	for child in theater_container.get_children():
		child.queue_free()

func _connect_mission_events(mission: MissionController) -> void:
	mission.sortie_barracks_destroyed.connect(_on_mission_barracks_destroyed)
	mission.sortie_hq_defeated.connect(_on_mission_hq_defeated)
	mission.sortie_player_defeated.connect(_on_mission_player_defeated)
	mission.sortie_checkpoint_changed.connect(_on_mission_checkpoint_changed)
	mission.sortie_pause_requested.connect(_on_mission_pause_requested)
	mission.sortie_return_to_menu_requested.connect(_on_mission_return_requested)

func _on_mission_barracks_destroyed(b_id: String, emp_idx: int, cred: int, pts: int, total_destroyed: int) -> void:
	_notify_bridge("onBarracksDestroyed", [b_id, emp_idx, cred, pts, total_destroyed])

func _on_mission_hq_defeated(emp_idx: int, score_val: int, kills_val: int, cred_val: int) -> void:
	_notify_bridge("onHqDefeated", [emp_idx, score_val, kills_val, cred_val])

func _on_mission_player_defeated(score_val: int, kills_val: int, barracks_val: int) -> void:
	_notify_bridge("onPlayerDefeated", [score_val, kills_val, barracks_val])

func _on_mission_checkpoint_changed(cp: Dictionary) -> void:
	_notify_bridge("onCheckpointUpdated", [JSON.stringify(cp)])

func _on_mission_pause_requested() -> void:
	set_battle_paused(true)
	_notify_bridge("onPauseRequested", [])

func _on_mission_return_requested() -> void:
	enter_standby()
	_notify_bridge("onReturnToMenuRequested", [])

func _notify_bridge(method_name: String, args: Array) -> void:
	if bridge_singleton != null and bridge_singleton.has_method(method_name):
		bridge_singleton.callv(method_name, args)
