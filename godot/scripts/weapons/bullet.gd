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
	if body == self or body is PlayerHelicopter or body.is_in_group("player"):
		return
	if body.has_method("take_damage"):
		body.take_damage(damage)
		queue_free()

func _on_area_entered(area: Area3D) -> void:
	if area == self or area is PlayerHelicopter or area.is_in_group("player") or area.is_in_group("player_projectiles"):
		return
	var parent := area.get_parent()
	if area.is_in_group("weak_points") and parent != null:
		if parent.has_method("take_weak_point_damage"):
			parent.take_weak_point_damage(damage)
			queue_free()
			return
		elif parent.has_method("hit_rear_boiler"):
			parent.hit_rear_boiler(damage)
			queue_free()
			return
	if area.has_method("take_damage"):
		area.take_damage(damage)
		queue_free()
