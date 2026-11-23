class_name OnlineService
extends Node

## Online Connection Service Stub (Phase 3 Audit Repair)
##
## PLACEHOLDER ENDPOINT ISSUE:
## Originally pointed to "https://api.helicopterwar.online/v1", which is a non-existent
## placeholder REST backend and does NOT integrate with this project's Firebase Auth +
## Cloud Firestore production backend ("hellwar-warzone").
## Fails closed (is_online = false) until bridged to Android WarNetCampaignService in Phase 5.

signal connection_status_changed(is_connected: bool, latency_ms: int, status_message: String)
signal profile_loaded(profile_data: Dictionary)
signal leaderboard_received(leaderboard_entries: Array)
signal error_occurred(error_code: String, error_msg: String)

const API_BASE_URL: String = "" # Disabled placeholder endpoint; routed via Kotlin Firebase in Phase 5
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
	if API_BASE_URL.is_empty():
		return
	heartbeat_timer += delta
	if heartbeat_timer >= HEARTBEAT_INTERVAL:
		heartbeat_timer = 0.0
		verify_heartbeat()

## Checks for actual active internet uplink (fail-closed when endpoint is unconfigured)
func check_connection() -> void:
	if API_BASE_URL.is_empty():
		is_online = false
		emit_signal("connection_status_changed", false, 0, "OFFLINE: WAR-NET ANDROID FIREBASE BRIDGE REQUIRED")
		return
	emit_signal("connection_status_changed", false, 0, "CONNECTING TO WAR-NET SATELLITE RELAY...")
	var error = http_client.request(API_BASE_URL + "/ping")
	if error != OK:
		is_online = false
		emit_signal("connection_status_changed", false, 0, "OFFLINE: NO NETWORK INTERFACE DETECTED")

func verify_heartbeat() -> void:
	if API_BASE_URL.is_empty():
		is_online = false
		return
	if not is_online:
		check_connection()
		return
	http_client.request(API_BASE_URL + "/heartbeat")

func _emit_unconfigured_error(action: String) -> void:
	is_online = false
	emit_signal("error_occurred", "ENDPOINT_UNCONFIGURED", action + " requires Android Firebase bridge (placeholder REST URL disabled).")

func authenticate_player(_auth_credentials: Dictionary) -> void:
	if API_BASE_URL.is_empty():
		_emit_unconfigured_error("authenticate_player")
		return
	var json_body = JSON.stringify(_auth_credentials)
	var headers = ["Content-Type: application/json"]
	http_client.request(API_BASE_URL + "/auth/login", headers, HTTPClient.METHOD_POST, json_body)

func fetch_leaderboard(limit: int = 25) -> void:
	if API_BASE_URL.is_empty():
		_emit_unconfigured_error("fetch_leaderboard")
		return
	var headers = ["Authorization: Bearer " + auth_token]
	http_client.request(API_BASE_URL + "/leaderboard/global?limit=" + str(limit), headers)

func sync_mission_progression(mission_payload: Dictionary) -> void:
	if API_BASE_URL.is_empty():
		_emit_unconfigured_error("sync_mission_progression")
		return
	var headers = ["Content-Type: application/json", "Authorization: Bearer " + auth_token]
	var json_body = JSON.stringify(mission_payload)
	http_client.request(API_BASE_URL + "/profile/progression", headers, HTTPClient.METHOD_POST, json_body)

## Synchronize Garage fleet inventory, unlocks, and skin choices
func sync_garage_inventory(garage_payload: Dictionary) -> void:
	if API_BASE_URL.is_empty():
		_emit_unconfigured_error("sync_garage_inventory")
		return
	var headers = ["Content-Type: application/json", "Authorization: Bearer " + auth_token]
	var json_body = JSON.stringify(garage_payload)
	http_client.request(API_BASE_URL + "/garage/sync", headers, HTTPClient.METHOD_POST, json_body)

## Server-side purchase & upgrade validation
func validate_upgrade_purchase(upgrade_request: Dictionary) -> void:
	if API_BASE_URL.is_empty():
		_emit_unconfigured_error("validate_upgrade_purchase")
		return
	var headers = ["Content-Type: application/json", "Authorization: Bearer " + auth_token]
	var json_body = JSON.stringify(upgrade_request)
	http_client.request(API_BASE_URL + "/garage/purchase", headers, HTTPClient.METHOD_POST, json_body)

func _on_http_request_completed(result: int, response_code: int, _headers: PackedStringArray, body: PackedByteArray) -> void:
	if API_BASE_URL.is_empty() or result != HTTPRequest.RESULT_SUCCESS or response_code < 200 or response_code >= 300:
		is_online = false
		emit_signal("connection_status_changed", false, 0, "SERVER DISCONNECTED (CODE " + str(response_code) + ")")
		return

	var json_parser = JSON.new()
	if json_parser.parse(body.get_string_from_utf8()) != OK or typeof(json_parser.get_data()) != TYPE_DICTIONARY:
		is_online = false
		emit_signal("connection_status_changed", false, 0, "SERVER PROTOCOL ERROR: INVALID RESPONSE")
		return

	is_online = true
	emit_signal("connection_status_changed", true, 42, "ONLINE: SECURE MIL-SPEC RELAY CONNECTED")
