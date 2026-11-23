class_name BarracksOutpost
extends Area3D

signal barracks_damaged(barracks_id: String, hits_remaining: int, max_hits: int)
signal barracks_destroyed(barracks_id: String, empire_index: int, credits_awarded: int, score_awarded: int)

@export var barracks_id: String = ""
@export var barracks_index: int = 1
@export var empire_index: int = 0
@export var max_hits: int = 100
@export var credits_reward: int = 250
@export var score_reward: int = 500

var hits_remaining: int = 100
var is_destroyed: bool = false
var is_discovered: bool = false
var is_alert_triggered: bool = false
var defenders_spawned: bool = false
var defender_types: Array[int] = []

var _mesh_instance: MeshInstance3D
var _status_light: MeshInstance3D
var _material: StandardMaterial3D
var _flash_timer: float = 0.0
var _base_color: Color = Color(0.55, 0.25, 0.18)

func configure(
	p_id: String,
	p_index: int,
	p_empire: int,
	p_color: Color,
	already_destroyed: bool,
	p_defenders: Array[int]
) -> void:
	barracks_id = p_id
	barracks_index = p_index
	empire_index = p_empire
	_base_color = p_color
	defender_types = p_defenders.duplicate()
	is_destroyed = already_destroyed
	is_discovered = already_destroyed
	hits_remaining = 0 if already_destroyed else max_hits
	add_to_group("enemies")
	add_to_group("barracks")
	_build_visual_if_needed()
	_apply_visual_state()

func _ready() -> void:
	add_to_group("enemies")
	add_to_group("barracks")
	_build_visual_if_needed()
	_apply_visual_state()

func _process(delta: float) -> void:
	if _flash_timer > 0.0:
		_flash_timer = maxf(0.0, _flash_timer - delta)
		if _flash_timer == 0.0:
			_apply_visual_state()

func _build_visual_if_needed() -> void:
	if get_child_count() > 0:
		return

	var col := CollisionShape3D.new()
	var box := BoxShape3D.new()
	box.size = Vector3(8.0, 5.0, 8.0)
	col.shape = box
	col.position = Vector3(0.0, 2.5, 0.0)
	add_child(col)

	_mesh_instance = MeshInstance3D.new()
	var mesh := BoxMesh.new()
	mesh.size = Vector3(8.0, 5.0, 8.0)
	_mesh_instance.mesh = mesh
	_mesh_instance.position = Vector3(0.0, 2.5, 0.0)
	_material = StandardMaterial3D.new()
	_material.albedo_color = _base_color
	_material.roughness = 0.55
	_material.metallic = 0.35
	_mesh_instance.material_override = _material
	add_child(_mesh_instance)

	_status_light = MeshInstance3D.new()
	var beacon := BoxMesh.new()
	beacon.size = Vector3(1.4, 0.6, 1.4)
	_status_light.mesh = beacon
	_status_light.position = Vector3(0.0, 5.3, 0.0)
	var beacon_mat := StandardMaterial3D.new()
	beacon_mat.albedo_color = Color(1.0, 0.3, 0.1)
	beacon_mat.emission_enabled = true
	beacon_mat.emission = Color(1.0, 0.25, 0.05)
	_status_light.material_override = beacon_mat
	add_child(_status_light)

func _apply_visual_state() -> void:
	if _material == null:
		return
	if is_destroyed:
		_material.albedo_color = Color(0.16, 0.17, 0.19)
		_material.emission_enabled = false
		if _mesh_instance:
			_mesh_instance.scale = Vector3(1.0, 0.28, 1.0)
			_mesh_instance.position = Vector3(0.0, 0.7, 0.0)
		if _status_light:
			_status_light.visible = false
	elif _flash_timer > 0.0:
		_material.albedo_color = Color(1.0, 0.95, 0.85)
		_material.emission_enabled = true
		_material.emission = Color(1.0, 0.7, 0.2)
	else:
		_material.albedo_color = _base_color
		_material.emission_enabled = false
		if _status_light:
			_status_light.visible = true

func take_damage(amount: float) -> void:
	if is_destroyed:
		return
	var hit_count := maxi(1, int(round(amount / 10.0)))
	hits_remaining = maxi(0, hits_remaining - hit_count)
	is_discovered = true
	_flash_timer = 0.09
	_apply_visual_state()
	emit_signal("barracks_damaged", barracks_id, hits_remaining, max_hits)

	if hits_remaining <= 0:
		is_destroyed = true
		_apply_visual_state()
		emit_signal("barracks_destroyed", barracks_id, empire_index, credits_reward, score_reward)
