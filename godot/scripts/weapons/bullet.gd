class_name Bullet
extends Area3D

@export var speed: float = 65.0
@export var damage: float = 12.0
@export var lifetime: float = 2.0

var elapsed_time: float = 0.0

func _ready() -> void:
	body_entered.connect(_on_body_entered)
	area_entered.connect(_on_area_entered)

func _physics_process(delta: float) -> void:
	# Fly forward towards top of screen (-Z)
	global_position.z -= speed * delta
	elapsed_time += delta
	if elapsed_time >= lifetime:
		queue_free()

func _on_body_entered(body: Node3D) -> void:
	if body.has_method("take_damage") and body != self:
		body.take_damage(damage)
		queue_free()

func _on_area_entered(area: Area3D) -> void:
	if area.has_method("take_damage") and area != self:
		area.take_damage(damage)
		queue_free()
