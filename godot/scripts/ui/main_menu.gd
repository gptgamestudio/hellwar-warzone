class_name MainMenu
extends Control

signal play_war_selected()
signal empire_campaign_selected()
signal endless_battle_selected()
signal garage_selected()
signal weapons_upgrades_selected()
signal war_command_selected()
signal leaderboard_selected()
signal settings_selected()

@onready var btn_play_war: Button = $VBoxContainer/BtnPlayWar
@onready var btn_empire_campaign: Button = $VBoxContainer/BtnEmpireCampaign
@onready var btn_endless_battle: Button = $VBoxContainer/BtnEndlessBattle
@onready var btn_garage: Button = $VBoxContainer/BtnGarage
@onready var btn_weapons: Button = $VBoxContainer/BtnWeapons
@onready var btn_command: Button = $VBoxContainer/BtnCommand
@onready var btn_leaderboard: Button = $VBoxContainer/BtnLeaderboard
@onready var btn_settings: Button = $VBoxContainer/BtnSettings
@onready var online_status_label: Label = $Header/OnlineStatusLabel
@onready var player_callsign: Label = $Header/CallsignLabel

func _ready() -> void:
	btn_play_war.pressed.connect(func(): emit_signal("play_war_selected"))
	btn_empire_campaign.pressed.connect(func(): emit_signal("empire_campaign_selected"))
	btn_endless_battle.pressed.connect(func(): emit_signal("endless_battle_selected"))
	btn_garage.pressed.connect(func(): emit_signal("garage_selected"))
	btn_weapons.pressed.connect(func(): emit_signal("weapons_upgrades_selected"))
	btn_command.pressed.connect(func(): emit_signal("war_command_selected"))
	btn_leaderboard.pressed.connect(func(): emit_signal("leaderboard_selected"))
	btn_settings.pressed.connect(func(): emit_signal("settings_selected"))
