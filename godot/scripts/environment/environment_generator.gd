class_name EnvironmentGenerator
extends Node3D

enum EmpireType { DRAGON, ALIEN, ORC, HUMAN }

@export var current_empire: EmpireType = EmpireType.DRAGON
@export var chunk_length: float = 60.0
@export var active_chunks_count: int = 4
@export var player_target: Node3D

@export_group("Environment Models")
@export var dragon_chunk_scene: PackedScene
@export var alien_chunk_scene: PackedScene
@export var orc_chunk_scene: PackedScene
@export var human_chunk_scene: PackedScene

var chunks: Array[Node3D] = []
var next_spawn_z: float = 0.0

func _ready() -> void:
	# Initialize first batch of seamless chunks ahead of camera
	for i in range(active_chunks_count):
		_spawn_chunk(next_spawn_z)
		next_spawn_z -= chunk_length

func _physics_process(_delta: float) -> void:
	if not is_instance_valid(player_target):
		return

	# Check if player has advanced far enough to recycle back chunks
	if chunks.size() > 0:
		var oldest_chunk = chunks[0]
		# If oldest chunk is behind player
		if oldest_chunk.global_position.z > (player_target.global_position.z + chunk_length):
			chunks.remove_at(0)
			oldest_chunk.queue_free()
			# Spawn new chunk forward seamlessly
			_spawn_chunk(next_spawn_z)
			next_spawn_z -= chunk_length

func _spawn_chunk(z_pos: float) -> void:
	var chunk: Node3D = Node3D.new()
	var chunk_scene: PackedScene = _resolve_empire_chunk_scene()
	if chunk_scene != null:
		var env_instance := chunk_scene.instantiate()
		if env_instance is Node3D:
			# Offset Y by -0.6 so top surface of 1.2m GLB terrain sits flush at GroundCollision (y = 0)
			env_instance.position = Vector3(0.0, -0.6, 0.0)
			chunk.add_child(env_instance)
		elif env_instance != null:
			env_instance.queue_free()
	add_child(chunk)
	chunk.global_position = Vector3(0.0, 0.0, z_pos)
	chunks.append(chunk)

func _resolve_empire_chunk_scene() -> PackedScene:
	var fallback_path: String = "res://assets/models/battlefield_environment.glb"
	var configured_scene: PackedScene = null
	match current_empire:
		EmpireType.DRAGON:
			configured_scene = dragon_chunk_scene
			fallback_path = "res://assets/models/env_dragon_volcano.glb"
		EmpireType.ALIEN:
			configured_scene = alien_chunk_scene
			fallback_path = "res://assets/models/env_alien_planet.glb"
		EmpireType.ORC:
			configured_scene = orc_chunk_scene
			fallback_path = "res://assets/models/env_orc_wasteland.glb"
		EmpireType.HUMAN:
			configured_scene = human_chunk_scene
			fallback_path = "res://assets/models/env_human_airbase.glb"

	if configured_scene != null:
		return configured_scene
	if ResourceLoader.exists(fallback_path):
		var loaded := load(fallback_path)
		if loaded is PackedScene:
			return loaded
	if ResourceLoader.exists("res://assets/models/battlefield_environment.glb"):
		var base_loaded := load("res://assets/models/battlefield_environment.glb")
		if base_loaded is PackedScene:
			return base_loaded
	return null

func transition_to_empire(new_empire: EmpireType) -> void:
	current_empire = new_empire
