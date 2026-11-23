class_name EmpireHeadquarters
extends Area3D

signal hq_locked_hit(barracks_destroyed: int)
signal hq_danger_zone_entered()
signal hq_damaged(hits_remaining: int, max_hits: int)
signal hq_destroyed(hq_id: String, empire_index: int)

@export var hq_id: String = ""
@export var empire_index: int = 0
@export var max_hits: int = 1000
@export var danger_zone_radius: float = 110.0

var hits_remaining: int = 1000
var is_unlocked: bool = false
var is_boss_defeated: bool = false
var is_destroyed: bool = false
var is_danger_zone_triggered: bool = false
var barracks_destroyed_count: int = 0

var _mesh_instance: MeshInstance3D
var _shield_dome: MeshInstance3D
var _material: StandardMaterial3D
var _flash_timer: float = 0.0
var _base_color: Color = Color(0.25, 0.30, 0.38)

func configure(
	p_id: String,
	p_empire: int,
	p_color: Color,
	p_unlocked: bool,
	p_boss_defeated: bool,
	p_destroyed: bool,
	p_hits_remaining: int = 1000
) -> void:
	hq_id = p_id
	empire_index = p_empire
	_base_color = p_color
	is_unlocked = p_unlocked
	is_boss_defeated = p_boss_defeated
	is_destroyed = p_destroyed
	hits_remaining = 0 if p_destroyed else clampi(p_hits_remaining, 1, max_hits)
	add_to_group("enemies")
	add_to_group("headquarters")
	_build_visual_if_needed()
	_apply_visual_state()

func _ready() -> void:
	add_to_group("enemies")
	add_to_group("headquarters")
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
	box.size = Vector3(16.0, 10.0, 16.0)
	col.shape = box
	col.position = Vector3(0.0, 5.0, 0.0)
	add_child(col)

	_mesh_instance = MeshInstance3D.new()
	var mesh := BoxMesh.new()
	mesh.size = Vector3(16.0, 10.0, 16.0)
	_mesh_instance.mesh = mesh
	_mesh_instance.position = Vector3(0.0, 5.0, 0.0)
	_material = StandardMaterial3D.new()
	_material.albedo_color = _base_color
	_material.metallic = 0.6
	_material.roughness = 0.35
	_mesh_instance.material_override = _material
	add_child(_mesh_instance)

	_shield_dome = MeshInstance3D.new()
	var sphere := SphereMesh.new()
	sphere.radius = 12.5
	sphere.height = 14.0
	_shield_dome.mesh = sphere
	_shield_dome.position = Vector3(0.0, 5.0, 0.0)
	var shield_mat := StandardMaterial3D.new()
	shield_mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	shield_mat.albedo_color = Color(0.1, 0.75, 1.0, 0.25)
	shield_mat.emission_enabled = true
	shield_mat.emission = Color(0.1, 0.7, 1.0)
	_shield_dome.material_override = shield_mat
	add_child(_shield_dome)

func _apply_visual_state() -> void:
	if _material == null:
		return
	if is_destroyed:
		_material.albedo_color = Color(0.12, 0.13, 0.15)
		_material.emission_enabled = false
		if _mesh_instance:
			_mesh_instance.scale = Vector3(1.0, 0.25, 1.0)
			_mesh_instance.position = Vector3(0.0, 1.25, 0.0)
		if _shield_dome:
			_shield_dome.visible = false
		return

	if _shield_dome:
		_shield_dome.visible = (not is_unlocked) or (not is_boss_defeated)

	if _flash_timer > 0.0:
		_material.albedo_color = Color(1.0, 0.92, 0.75)
		_material.emission_enabled = true
		_material.emission = Color(1.0, 0.5, 0.1)
	else:
		_material.albedo_color = _base_color
		_material.emission_enabled = is_unlocked and is_boss_defeated
		_material.emission = Color(0.9, 0.2, 0.1) if (is_unlocked and is_boss_defeated) else Color.BLACK

func set_barracks_cleared(cleared_count: int) -> void:
	barracks_destroyed_count = cleared_count
	if cleared_count >= 50:
		is_unlocked = true
	_apply_visual_state()

func mark_boss_defeated() -> void:
	if not is_unlocked:
		return
	is_boss_defeated = true
	_apply_visual_state()

func trigger_danger_zone() -> void:
	if is_danger_zone_triggered:
		return
	is_danger_zone_triggered = true
	emit_signal("hq_danger_zone_entered")

func take_damage(amount: float) -> void:
	if is_destroyed:
		return
	# Strict 50-barracks + Boss gate: HQ cannot be damaged while locked or before boss is defeated
	if not is_unlocked:
		emit_signal("hq_locked_hit", barracks_destroyed_count)
		return
	if not is_boss_defeated:
		return

	var hit_count := maxi(1, int(round(amount / 10.0)))
	hits_remaining = maxi(0, hits_remaining - hit_count)
	_flash_timer = 0.08
	_apply_visual_state()
	emit_signal("hq_damaged", hits_remaining, max_hits)

	if hits_remaining <= 0:
		is_destroyed = true
		_apply_visual_state()
		emit_signal("hq_destroyed", hq_id, empire_index)
