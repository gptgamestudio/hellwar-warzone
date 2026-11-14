class_name GameHUD
extends CanvasLayer

@onready var health_bar: ProgressBar = $TopBar/HealthBar
@onready var armor_bar: ProgressBar = $TopBar/ArmorBar
@onready var score_label: Label = $TopBar/ScoreLabel
@onready var kills_label: Label = $TopBar/KillsLabel
@onready var mission_progress_bar: ProgressBar = $TopBar/MissionProgressBar
@onready var threat_warning: Control = $ThreatWarningPanel
@onready var radar_minimap: Control = $RadarMinimap
@onready var missile_button: Button = $Controls/MissileButton
@onready var missile_cooldown_overlay: TextureProgressBar = $Controls/MissileButton/CooldownOverlay
@onready var btn_up: TouchScreenButton = $Controls/DPad/BtnUp
@onready var btn_down: TouchScreenButton = $Controls/DPad/BtnDown
@onready var btn_left: TouchScreenButton = $Controls/DPad/BtnLeft
@onready var btn_right: TouchScreenButton = $Controls/DPad/BtnRight

func update_health(current: float, maximum: float) -> void:
	if health_bar:
		health_bar.max_value = maximum
		health_bar.value = current

func update_armor(current: float, maximum: float) -> void:
	if armor_bar:
		armor_bar.max_value = maximum
		armor_bar.value = current

func update_score(score: int) -> void:
	if score_label:
		score_label.text = "SCORE: " + str(score)

func update_kills(kills: int) -> void:
	if kills_label:
		kills_label.text = "KILLS: " + str(kills)

func update_mission_progress(percent: float) -> void:
	if mission_progress_bar:
		mission_progress_bar.value = percent

func show_threat_warning(message: String) -> void:
	if threat_warning:
		threat_warning.visible = true
		$ThreatWarningPanel/WarningLabel.text = message

func hide_threat_warning() -> void:
	if threat_warning:
		threat_warning.visible = false

func set_missile_cooldown(progress: float, is_ready: bool) -> void:
	if missile_cooldown_overlay:
		missile_cooldown_overlay.value = progress * 100.0
	if missile_button:
		missile_button.disabled = not is_ready
