class_name GameHUD
extends CanvasLayer

signal pause_pressed()
signal auto_fire_toggled(enabled: bool)
signal missile_fired()
signal rocket_fired()
signal flare_fired()
signal overdrive_fired()
signal move_input_changed(direction: Vector2)

@onready var health_bar: ProgressBar = $TopBar/HealthBar
@onready var armor_bar: ProgressBar = $TopBar/ArmorBar
@onready var shield_bar: ProgressBar = $TopBar/ShieldBar
@onready var hearts_label: Label = $TopBar/HeartsLabel
@onready var score_label: Label = $TopBar/ScoreLabel
@onready var kills_label: Label = $TopBar/KillsLabel
@onready var objective_label: Label = $TopBar/ObjectiveLabel
@onready var mission_progress_bar: ProgressBar = $TopBar/MissionProgressBar
@onready var pause_button: Button = $TopBar/PauseButton
@onready var boss_panel: Panel = $BossHealthPanel
@onready var boss_name_label: Label = $BossHealthPanel/BossNameLabel
@onready var boss_health_bar: ProgressBar = $BossHealthPanel/BossHealthBar
@onready var threat_warning: Control = $ThreatWarningPanel
@onready var radar_minimap: Control = $RadarMinimap
@onready var missile_button: Button = $Controls/MissileButton
@onready var missile_cooldown_overlay: TextureProgressBar = $Controls/MissileButton/CooldownOverlay
@onready var rocket_button: Button = $Controls/RocketButton
@onready var flare_button: Button = $Controls/FlareButton
@onready var overdrive_button: Button = $Controls/OverdriveButton
@onready var autofire_button: Button = $Controls/AutoFireButton
@onready var btn_up: TouchScreenButton = $Controls/DPad/BtnUp
@onready var btn_down: TouchScreenButton = $Controls/DPad/BtnDown
@onready var btn_left: TouchScreenButton = $Controls/DPad/BtnLeft
@onready var btn_right: TouchScreenButton = $Controls/DPad/BtnRight

var _dpad_up: bool = false
var _dpad_down: bool = false
var _dpad_left: bool = false
var _dpad_right: bool = false
var _auto_fire: bool = true

func _ready() -> void:
	if pause_button:
		pause_button.pressed.connect(func(): emit_signal("pause_pressed"))
	if missile_button:
		missile_button.pressed.connect(func(): emit_signal("missile_fired"))
	if rocket_button:
		rocket_button.pressed.connect(func(): emit_signal("rocket_fired"))
	if flare_button:
		flare_button.pressed.connect(func(): emit_signal("flare_fired"))
	if overdrive_button:
		overdrive_button.pressed.connect(func(): emit_signal("overdrive_fired"))
	if autofire_button:
		autofire_button.pressed.connect(_on_autofire_pressed)

	_bind_touch_button(btn_up, "up")
	_bind_touch_button(btn_down, "down")
	_bind_touch_button(btn_left, "left")
	_bind_touch_button(btn_right, "right")

func _bind_touch_button(btn: TouchScreenButton, dir: String) -> void:
	if btn == null:
		return
	btn.pressed.connect(func(): _set_dir(dir, true))
	btn.released.connect(func(): _set_dir(dir, false))

func _set_dir(dir: String, pressed: bool) -> void:
	match dir:
		"up": _dpad_up = pressed
		"down": _dpad_down = pressed
		"left": _dpad_left = pressed
		"right": _dpad_right = pressed
	var x := (1.0 if _dpad_right else 0.0) - (1.0 if _dpad_left else 0.0)
	var y := (1.0 if _dpad_down else 0.0) - (1.0 if _dpad_up else 0.0)
	emit_signal("move_input_changed", Vector2(x, y))

func _on_autofire_pressed() -> void:
	_auto_fire = not _auto_fire
	if autofire_button:
		autofire_button.text = "AUTO: ON" if _auto_fire else "AUTO: OFF"
	emit_signal("auto_fire_toggled", _auto_fire)

func update_health(current: float, maximum: float) -> void:
	if health_bar:
		health_bar.max_value = maxf(1.0, maximum)
		health_bar.value = current

func update_armor(current: float, maximum: float) -> void:
	if armor_bar:
		armor_bar.max_value = maxf(1.0, maximum)
		armor_bar.value = current

func update_shield(current: float, maximum: float) -> void:
	if shield_bar:
		shield_bar.max_value = maxf(1.0, maximum)
		shield_bar.value = current

func update_hearts(hearts: int, max_hearts: int, hits_left: int, hits_per: int) -> void:
	if hearts_label:
		hearts_label.text = "HEARTS: %d/%d (%d/%d HITS)" % [hearts, max_hearts, hits_left, hits_per]

func update_score(score: int) -> void:
	if score_label:
		score_label.text = "SCORE: " + str(score)

func update_kills(kills: int) -> void:
	if kills_label:
		kills_label.text = "KILLS: " + str(kills)

func update_mission_progress(percent: float) -> void:
	if mission_progress_bar:
		mission_progress_bar.value = percent

func update_objective_status(
	barracks_cleared: int,
	total_barracks: int,
	hq_unlocked: bool,
	boss_active: bool,
	boss_defeated: bool,
	hq_hits: int,
	hq_max: int
) -> void:
	if objective_label == null:
		return
	if boss_defeated:
		objective_label.text = "SIEGE HQ: %d / %d HITS" % [hq_hits, hq_max]
	elif hq_unlocked:
		objective_label.text = "HQ BOSS ENGAGED" if boss_active else "HQ UNLOCKED: FLY TO APEX"
	else:
		objective_label.text = "BARRACKS CLEARED: %d / %d" % [barracks_cleared, total_barracks]

func update_boss_health(b_name: String, current: float, maximum: float, phase: int) -> void:
	if boss_panel:
		boss_panel.visible = true
	if boss_name_label:
		boss_name_label.text = "%s [PHASE %d]" % [b_name.to_upper(), phase]
	if boss_health_bar:
		boss_health_bar.max_value = maxf(1.0, maximum)
		boss_health_bar.value = current

func hide_boss_health() -> void:
	if boss_panel:
		boss_panel.visible = false

func show_threat_warning(message: String) -> void:
	if threat_warning:
		threat_warning.visible = true
		$ThreatWarningPanel/WarningLabel.text = message

func hide_threat_warning() -> void:
	if threat_warning:
		threat_warning.visible = false

func set_missile_cooldown(progress: float, is_ready: bool, ammo: int = -1, max_ammo: int = -1) -> void:
	if missile_cooldown_overlay:
		missile_cooldown_overlay.value = progress * 100.0
	if missile_button:
		missile_button.disabled = not is_ready
		if ammo >= 0 and max_ammo >= 0:
			missile_button.text = "MISSILE (%d)" % ammo

func set_rocket_state(_progress: float, is_ready: bool, ammo: int, _max_ammo: int) -> void:
	if rocket_button:
		rocket_button.disabled = not is_ready
		rocket_button.text = "ROCKET (%d)" % ammo

func set_flare_state(_progress: float, is_ready: bool) -> void:
	if flare_button:
		flare_button.disabled = not is_ready
		flare_button.text = "FLARE" if is_ready else "FLARE..."

func set_overdrive_state(charge_pct: float, is_ready: bool) -> void:
	if overdrive_button:
		overdrive_button.disabled = not is_ready
		overdrive_button.text = "OVERDRIVE" if is_ready else "OD %d%%" % int(round(charge_pct * 100.0))
