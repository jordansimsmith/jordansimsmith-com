"""Anki Backup - automatic collection backup after sync."""

import base64
import json
import urllib.error
import urllib.request

# -- configuration (edit these for your setup) --

ANKI_BACKUP_API_URL = "https://api.anki-backup.jordansimsmith.com"
ANKI_BACKUP_USER = ""
ANKI_BACKUP_PASSWORD = ""

PART_SIZE_BYTES = 67_108_864


# -- toast helpers --


def _show_toast(message, kind="info"):
    try:
        from aqt import mw
        from aqt.utils import tooltip

        if mw is not None:
            prefix = {"success": "Backup: ", "error": "Backup error: "}.get(
                kind, "Backup: "
            )
            tooltip(f"{prefix}{message}", parent=mw)
            return
    except ImportError:
        pass
    print(f"[anki_backup] [{kind}] {message}")


def toast_info(message):
    _show_toast(message, "info")


def toast_success(message):
    _show_toast(message, "success")


def toast_error(message):
    _show_toast(message, "error")


# -- api client --


class AnkiBackupApiError(Exception):
    def __init__(self, status_code, message):
        self.status_code = status_code
        self.message = message
        super().__init__(f"HTTP {status_code}: {message}")


class AnkiBackupClient:
    def __init__(self, base_url, user, password):
        self._base_url = base_url.rstrip("/")
        self._auth_header = (
            "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()
        )

    def _request(self, method, path, body=None):
        url = f"{self._base_url}{path}"
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(
            url,
            data=data,
            method=method,
            headers={
                "Authorization": self._auth_header,
                "Content-Type": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(req) as resp:
                return json.loads(resp.read().decode())
        except urllib.error.HTTPError as e:
            error_body = e.read().decode() if e.fp else ""
            try:
                error_json = json.loads(error_body)
                message = error_json.get("message", error_body)
            except (json.JSONDecodeError, ValueError):
                message = error_body
            raise AnkiBackupApiError(e.code, message) from e

    def create_backup(self, profile_id, artifact):
        return self._request(
            "POST",
            "/backups",
            {"profile_id": profile_id, "artifact": artifact},
        )

    def update_backup(self, backup_id, status):
        return self._request("PUT", f"/backups/{backup_id}", {"status": status})

    def find_backups(self):
        return self._request("GET", "/backups")

    def get_backup(self, backup_id):
        return self._request("GET", f"/backups/{backup_id}")


# -- smoke test --

if __name__ == "__main__":
    import sys

    print("=== anki_backup add-on smoke test ===")

    # validate auth header construction
    client = AnkiBackupClient("https://example.com", "alice", "p@ss:word")
    expected_cred = base64.b64encode(b"alice:p@ss:word").decode()
    assert client._auth_header == f"Basic {expected_cred}", "auth header mismatch"
    print("[pass] auth header construction (including colons in password)")

    # validate URL building
    assert client._base_url == "https://example.com", "base URL mismatch"
    client2 = AnkiBackupClient("https://example.com/", "u", "p")
    assert client2._base_url == "https://example.com", "trailing slash not stripped"
    print("[pass] URL construction and trailing slash handling")

    # validate error class
    err = AnkiBackupApiError(401, "unauthorized")
    assert err.status_code == 401
    assert err.message == "unauthorized"
    assert "401" in str(err)
    print("[pass] AnkiBackupApiError construction")

    # validate toast helpers run without error outside Anki
    toast_info("test info")
    toast_success("test success")
    toast_error("test error")
    print("[pass] toast helpers (fallback to print)")

    # validate HTTP error handling with a mock server
    from http.server import BaseHTTPRequestHandler, HTTPServer
    import threading

    class MockHandler(BaseHTTPRequestHandler):
        def do_POST(self):
            length = int(self.headers.get("Content-Length", 0))
            self.rfile.read(length)
            if self.path == "/backups":
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"status": "skipped"}).encode())
            else:
                self.send_response(404)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"message": "not found"}).encode())

        def do_GET(self):
            if self.path == "/backups":
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"backups": []}).encode())
            elif self.path.startswith("/backups/"):
                self.send_response(404)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"message": "backup not found"}).encode())

        def log_message(self, format, *args):
            pass

    server = HTTPServer(("127.0.0.1", 0), MockHandler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever)
    thread.daemon = True
    thread.start()

    test_client = AnkiBackupClient(f"http://127.0.0.1:{port}", "alice", "password")

    # test successful create_backup (skipped)
    result = test_client.create_backup(
        "main",
        {
            "filename": "test.colpkg",
            "size_bytes": 1024,
            "sha256": "abc123",
        },
    )
    assert result["status"] == "skipped", f"expected skipped, got {result}"
    print("[pass] create_backup API call (skipped response)")

    # test successful find_backups
    result = test_client.find_backups()
    assert result["backups"] == [], f"expected empty list, got {result}"
    print("[pass] find_backups API call (empty list)")

    # test error handling (404 on get_backup)
    try:
        test_client.get_backup("nonexistent-id")
        assert False, "expected AnkiBackupApiError"
    except AnkiBackupApiError as e:
        assert e.status_code == 404
        assert e.message == "backup not found"
    print("[pass] get_backup error handling (404 with JSON message)")

    server.shutdown()
    print("\n=== all smoke tests passed ===")
    sys.exit(0)
