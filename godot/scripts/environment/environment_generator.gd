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
	add_child(chunk)
	chunk.global_position = Vector3(0.0, 0.0, z_pos)
	chunks.append(chunk)

func transition_to_empire(new_empire: EmpireType) -> void:
	current_empire = new_empire
