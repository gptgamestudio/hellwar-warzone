class_name HazardBase
extends Area3D

signal hazard_triggered(damage: float)

enum HazardType { LAVA_GEYSER, PLASMA_LIGHTNING, SMOG_FIREPIT, EMP_GRID }

@export var hazard_type: HazardType = HazardType.LAVA_GEYSER
@export var damage: float = 25.0
@export var activation_interval: float = 3.5
@export var active_duration: float = 1.2
@export var warning_duration: float = 0.8

var timer: float = 0.0
var is_active: bool = false
var is_warning: bool = false

func _ready() -> void:
	body_entered.connect(_on_body_entered)
	area_entered.connect(_on_area_entered)

func _physics_process(delta: float) -> void:
	timer += delta
	if not is_warning and not is_active and timer >= (activation_interval - warning_duration):
		is_warning = true
		_on_warning_started()

	if is_warning and not is_active and timer >= activation_interval:
		is_warning = false
		is_active = true
		_on_activated()

	if is_active and timer >= (activation_interval + active_duration):
		is_active = false
		timer = 0.0
		_on_deactivated()

func _on_warning_started() -> void:
	# Show warning glow
	pass

func _on_activated() -> void:
	# Erupt lava / discharge lightning
	pass

func _on_deactivated() -> void:
	# Reset visual
	pass

func _on_body_entered(body: Node3D) -> void:
	if is_active and body.has_method("take_damage") and body is PlayerHelicopter:
		body.take_damage(damage)
		emit_signal("hazard_triggered", damage)

func _on_area_entered(area: Area3D) -> void:
	if is_active and area.has_method("take_damage") and area.get_parent() is PlayerHelicopter:
		area.get_parent().take_damage(damage)
		emit_signal("hazard_triggered", damage)
