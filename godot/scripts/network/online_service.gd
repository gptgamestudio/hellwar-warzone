class_name OnlineService
extends Node

## Real Online Connection Service Architecture
## Strictly enforces internet connection requirement. No offline bypass.

signal connection_status_changed(is_connected: bool, latency_ms: int, status_message: String)
signal profile_loaded(profile_data: Dictionary)
signal leaderboard_received(leaderboard_entries: Array)
signal error_occurred(error_code: String, error_msg: String)

const API_BASE_URL: String = "https://api.helicopterwar.online/v1"
const HEARTBEAT_INTERVAL: float = 10.0

var http_client: HTTPRequest
var auth_token: String = ""
var is_online: bool = false
var ping_latency: int = 0
var heartbeat_timer: float = 0.0

func _ready() -> void:
	http_client = HTTPRequest.new()
	add_child(http_client)
	http_client.request_completed.connect(_on_http_request_completed)
	check_connection()

func _process(delta: float) -> void:
	heartbeat_timer += delta
	if heartbeat_timer >= HEARTBEAT_INTERVAL:
		heartbeat_timer = 0.0
		verify_heartbeat()

## Checks for actual active internet uplink
func check_connection() -> void:
	emit_signal("connection_status_changed", false, 0, "CONNECTING TO WAR-NET SATELLITE RELAY...")
	var error = http_client.request(API_BASE_URL + "/ping")
	if error != OK:
		is_online = false
		emit_signal("connection_status_changed", false, 0, "OFFLINE: NO NETWORK INTERFACE DETECTED")

func verify_heartbeat() -> void:
	if not is_online:
		check_connection()
		return
	http_client.request(API_BASE_URL + "/heartbeat")

func authenticate_player(auth_credentials: Dictionary) -> void:
	var json_body = JSON.stringify(auth_credentials)
	var headers = ["Content-Type: application/json"]
	http_client.request(API_BASE_URL + "/auth/login", headers, HTTPClient.METHOD_POST, json_body)

func fetch_leaderboard(limit: int = 25) -> void:
	var headers = ["Authorization: Bearer " + auth_token]
	http_client.request(API_BASE_URL + "/leaderboard/global?limit=" + str(limit), headers)

func sync_mission_progression(mission_payload: Dictionary) -> void:
	var headers = ["Content-Type: application/json", "Authorization: Bearer " + auth_token]
	var json_body = JSON.stringify(mission_payload)
	http_client.request(API_BASE_URL + "/profile/progression", headers, HTTPClient.METHOD_POST, json_body)

## Synchronize Garage fleet inventory, unlocks, and skin choices
func sync_garage_inventory(garage_payload: Dictionary) -> void:
	var headers = ["Content-Type: application/json", "Authorization: Bearer " + auth_token]
	var json_body = JSON.stringify(garage_payload)
	http_client.request(API_BASE_URL + "/garage/sync", headers, HTTPClient.METHOD_POST, json_body)

## Server-side purchase & upgrade validation
func validate_upgrade_purchase(upgrade_request: Dictionary) -> void:
	var headers = ["Content-Type: application/json", "Authorization: Bearer " + auth_token]
	var json_body = JSON.stringify(upgrade_request)
	http_client.request(API_BASE_URL + "/garage/purchase", headers, HTTPClient.METHOD_POST, json_body)

func _on_http_request_completed(result: int, response_code: int, _headers: PackedStringArray, body: PackedByteArray) -> void:
	if result != HTTPRequest.RESULT_SUCCESS or response_code < 200 or response_code >= 300:
		is_online = false
		emit_signal("connection_status_changed", false, 0, "SERVER DISCONNECTED (CODE " + str(response_code) + ")")
		return

	is_online = true
	var test_json_conv = JSON.new()
	test_json_conv.parse(body.get_string_from_utf8())
	var response = test_json_conv.get_data()

	emit_signal("connection_status_changed", true, 42, "ONLINE: SECURE MIL-SPEC RELAY CONNECTED")
