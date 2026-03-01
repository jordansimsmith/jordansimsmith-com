"""Anki Backup - automatic collection backup after sync."""

import base64
import datetime
import hashlib
import json
import os
import tempfile
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


# -- artifact helpers --


def describe_artifact(path):
    filename = os.path.basename(path)
    size_bytes = os.path.getsize(path)
    sha = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            sha.update(chunk)
    return {
        "filename": filename,
        "size_bytes": size_bytes,
        "sha256": sha.hexdigest(),
    }


def _cleanup_temp_file(path):
    try:
        os.unlink(path)
        os.rmdir(os.path.dirname(path))
    except OSError:
        pass


# -- sync hook --


def _get_profile_id():
    from aqt import mw

    return mw.pm.name


def _export_colpkg():
    from aqt import mw

    tmpdir = tempfile.mkdtemp()
    filename = f"collection-{datetime.date.today().isoformat()}.colpkg"
    out_path = os.path.join(tmpdir, filename)
    mw.col.export_collection_package(out_path, include_media=True, legacy=False)
    return out_path


def on_sync_did_finish():
    if not ANKI_BACKUP_USER or not ANKI_BACKUP_PASSWORD:
        return

    colpkg_path = None
    try:
        profile_id = _get_profile_id()
        colpkg_path = _export_colpkg()
        artifact = describe_artifact(colpkg_path)

        client = AnkiBackupClient(
            ANKI_BACKUP_API_URL, ANKI_BACKUP_USER, ANKI_BACKUP_PASSWORD
        )
        result = client.create_backup(profile_id, artifact)

        if result["status"] == "skipped":
            toast_info("backup skipped (already backed up recently)")
            return

        backup_id = result["backup"]["backup_id"]
        upload = result["upload"]
        _upload_and_complete(client, colpkg_path, backup_id, upload)
    except Exception as e:
        toast_error(str(e))
    finally:
        if colpkg_path is not None:
            _cleanup_temp_file(colpkg_path)


def _upload_and_complete(client, colpkg_path, backup_id, upload):
    part_size = upload["part_size_bytes"]
    parts = upload["parts"]

    with open(colpkg_path, "rb") as f:
        for part in parts:
            data = f.read(part_size)
            req = urllib.request.Request(
                part["upload_url"],
                data=data,
                method="PUT",
            )
            try:
                with urllib.request.urlopen(req) as resp:
                    resp.read()
            except urllib.error.HTTPError as e:
                raise AnkiBackupApiError(
                    e.code, f"failed to upload part {part['part_number']}"
                ) from e

    client.update_backup(backup_id, "COMPLETED")
    toast_success("backup completed successfully")


# -- restore discovery --


def _format_backup_line(backup):
    created = backup.get("created_at", "unknown")
    profile = backup.get("profile_id", "unknown")
    size_mb = backup.get("size_bytes", 0) / (1024 * 1024)
    return f"{created}  |  {profile}  |  {size_mb:.1f} MB"


def _get_download_url(client, backup_id):
    result = client.get_backup(backup_id)
    backup = result["backup"]
    return backup["download_url"], backup["download_url_expires_at"]


def show_backup_history():
    if not ANKI_BACKUP_USER or not ANKI_BACKUP_PASSWORD:
        toast_error("credentials not configured")
        return

    try:
        client = AnkiBackupClient(
            ANKI_BACKUP_API_URL, ANKI_BACKUP_USER, ANKI_BACKUP_PASSWORD
        )
        result = client.find_backups()
        backups = result.get("backups", [])

        if not backups:
            toast_info("no backups available")
            return

        _show_backup_list_dialog(client, backups)
    except Exception as e:
        toast_error(str(e))


def _show_backup_list_dialog(client, backups):
    import webbrowser

    from aqt import mw
    from aqt.qt import (
        QDialog,
        QHBoxLayout,
        QLabel,
        QListWidget,
        QPushButton,
        QVBoxLayout,
    )

    dialog = QDialog(mw)
    dialog.setWindowTitle("Anki Backup - Restore History")
    dialog.setMinimumWidth(500)
    dialog.setMinimumHeight(400)

    layout = QVBoxLayout()
    layout.addWidget(QLabel("Select a backup to download for manual restore:"))

    list_widget = QListWidget()
    for backup in backups:
        list_widget.addItem(_format_backup_line(backup))
    layout.addWidget(list_widget)
    list_widget.setCurrentRow(0)

    instructions = QLabel(
        "To restore: download the .colpkg file, then use "
        "File > Import in Anki to restore the collection."
    )
    instructions.setWordWrap(True)
    layout.addWidget(instructions)

    button_layout = QHBoxLayout()
    download_btn = QPushButton("Download Backup")
    close_btn = QPushButton("Close")
    button_layout.addWidget(download_btn)
    button_layout.addWidget(close_btn)
    layout.addLayout(button_layout)

    def on_download():
        row = list_widget.currentRow()
        if row < 0:
            toast_info("select a backup first")
            return
        backup_id = backups[row]["backup_id"]
        try:
            url, expires_at = _get_download_url(client, backup_id)
            webbrowser.open(url)
            toast_success(f"download opened in browser (link expires {expires_at})")
        except Exception as e:
            toast_error(str(e))

    download_btn.clicked.connect(on_download)
    close_btn.clicked.connect(dialog.close)

    dialog.setLayout(layout)
    dialog.exec()


try:
    from aqt import gui_hooks, mw
    from aqt.qt import QAction

    gui_hooks.sync_did_finish.append(on_sync_did_finish)

    def _setup_menu():
        action = QAction("Anki Backup History...", mw)
        action.triggered.connect(show_backup_history)
        mw.form.menuTools.addAction(action)

    gui_hooks.profile_did_open.append(_setup_menu)
except ImportError:
    pass


# -- smoke test --

if __name__ == "__main__":
    import sys
    import unittest.mock

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

    # validate describe_artifact with a known temp file
    tmpdir = tempfile.mkdtemp()
    test_path = os.path.join(tmpdir, "test-collection.colpkg")
    test_content = b"fake colpkg content for testing"
    with open(test_path, "wb") as f:
        f.write(test_content)
    art = describe_artifact(test_path)
    assert art["filename"] == "test-collection.colpkg", f"filename: {art['filename']}"
    assert art["size_bytes"] == len(test_content), f"size_bytes: {art['size_bytes']}"
    expected_sha = hashlib.sha256(test_content).hexdigest()
    assert art["sha256"] == expected_sha, f"sha256: {art['sha256']}"
    print("[pass] describe_artifact (filename, size_bytes, sha256)")

    # validate _cleanup_temp_file removes file and directory
    _cleanup_temp_file(test_path)
    assert not os.path.exists(test_path), "temp file not removed"
    assert not os.path.exists(tmpdir), "temp dir not removed"
    _cleanup_temp_file("/nonexistent/path/file.tmp")
    print("[pass] _cleanup_temp_file (removes file+dir, ignores missing)")

    # validate HTTP error handling with a mock server
    from http.server import BaseHTTPRequestHandler, HTTPServer
    import threading

    uploaded_parts = {}

    class MockHandler(BaseHTTPRequestHandler):
        def do_POST(self):
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length)
            if self.path == "/backups":
                req_json = json.loads(body.decode())
                if req_json.get("_test_mode") == "ready":
                    self.send_response(201)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(
                        json.dumps(
                            {
                                "status": "ready",
                                "backup": {
                                    "backup_id": "test-backup-id",
                                    "profile_id": req_json["profile_id"],
                                    "status": "PENDING",
                                    "created_at": "2026-03-01T10:00:00Z",
                                    "completed_at": None,
                                    "size_bytes": req_json["artifact"]["size_bytes"],
                                    "sha256": req_json["artifact"]["sha256"],
                                    "expires_at": "2026-05-30T10:00:00Z",
                                    "download_url": None,
                                    "download_url_expires_at": None,
                                },
                                "upload": {
                                    "part_size_bytes": 16,
                                    "expires_at": "2026-03-01T11:00:00Z",
                                    "parts": [
                                        {
                                            "part_number": 1,
                                            "upload_url": f"http://127.0.0.1:{port}/s3/part/1",
                                        },
                                        {
                                            "part_number": 2,
                                            "upload_url": f"http://127.0.0.1:{port}/s3/part/2",
                                        },
                                    ],
                                },
                            }
                        ).encode()
                    )
                else:
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(json.dumps({"status": "skipped"}).encode())
            else:
                self.send_response(404)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"message": "not found"}).encode())

        def do_PUT(self):
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length)
            if self.path.startswith("/s3/part/"):
                part_num = self.path.split("/")[-1]
                uploaded_parts[part_num] = body
                self.send_response(200)
                self.send_header("ETag", f'"etag-{part_num}"')
                self.end_headers()
            elif self.path.startswith("/backups/"):
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"status": "completed"}).encode())
            else:
                self.send_response(404)
                self.end_headers()

        def do_GET(self):
            if self.path == "/backups":
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(
                    json.dumps(
                        {
                            "backups": [
                                {
                                    "backup_id": "backup-001",
                                    "profile_id": "main",
                                    "status": "COMPLETED",
                                    "created_at": "2026-03-01T10:00:00Z",
                                    "completed_at": "2026-03-01T10:01:00Z",
                                    "size_bytes": 534773760,
                                    "sha256": "abc123",
                                    "expires_at": "2026-05-30T10:00:00Z",
                                    "download_url": None,
                                    "download_url_expires_at": None,
                                },
                                {
                                    "backup_id": "backup-002",
                                    "profile_id": "japanese",
                                    "status": "COMPLETED",
                                    "created_at": "2026-02-28T10:00:00Z",
                                    "completed_at": "2026-02-28T10:01:00Z",
                                    "size_bytes": 104857600,
                                    "sha256": "def456",
                                    "expires_at": "2026-05-29T10:00:00Z",
                                    "download_url": None,
                                    "download_url_expires_at": None,
                                },
                            ]
                        }
                    ).encode()
                )
            elif self.path.startswith("/backups/"):
                backup_id = self.path.split("/")[-1]
                if backup_id == "nonexistent-id":
                    self.send_response(404)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(
                        json.dumps({"message": "backup not found"}).encode()
                    )
                else:
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(
                        json.dumps(
                            {
                                "backup": {
                                    "backup_id": backup_id,
                                    "profile_id": "main",
                                    "status": "COMPLETED",
                                    "created_at": "2026-03-01T10:00:00Z",
                                    "completed_at": "2026-03-01T10:01:00Z",
                                    "size_bytes": 534773760,
                                    "sha256": "abc123",
                                    "expires_at": "2026-05-30T10:00:00Z",
                                    "download_url": f"http://127.0.0.1:{port}/s3/download/{backup_id}",
                                    "download_url_expires_at": "2026-03-01T11:00:00Z",
                                }
                            }
                        ).encode()
                    )

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
    assert len(result["backups"]) == 2, (
        f"expected 2 backups, got {len(result['backups'])}"
    )
    assert result["backups"][0]["backup_id"] == "backup-001"
    assert result["backups"][1]["backup_id"] == "backup-002"
    print("[pass] find_backups API call (2 backups returned)")

    # test error handling (404 on get_backup)
    try:
        test_client.get_backup("nonexistent-id")
        assert False, "expected AnkiBackupApiError"
    except AnkiBackupApiError as e:
        assert e.status_code == 404
        assert e.message == "backup not found"
    print("[pass] get_backup error handling (404 with JSON message)")

    # test _upload_and_complete with mock S3 upload and API completion
    upload_tmpdir = tempfile.mkdtemp()
    upload_colpkg = os.path.join(upload_tmpdir, "upload-test.colpkg")
    test_data = b"0123456789abcdef" + b"GHIJKLMNOPQRSTUV"
    with open(upload_colpkg, "wb") as f:
        f.write(test_data)

    uploaded_parts.clear()
    upload_client = AnkiBackupClient(f"http://127.0.0.1:{port}", "alice", "password")
    upload_info = {
        "part_size_bytes": 16,
        "parts": [
            {"part_number": 1, "upload_url": f"http://127.0.0.1:{port}/s3/part/1"},
            {"part_number": 2, "upload_url": f"http://127.0.0.1:{port}/s3/part/2"},
        ],
    }
    _upload_and_complete(upload_client, upload_colpkg, "test-backup-id", upload_info)
    assert uploaded_parts["1"] == b"0123456789abcdef", "part 1 data mismatch"
    assert uploaded_parts["2"] == b"GHIJKLMNOPQRSTUV", "part 2 data mismatch"
    _cleanup_temp_file(upload_colpkg)
    print("[pass] _upload_and_complete (multipart upload + completion)")

    # test on_sync_did_finish with ready path (full flow)
    sync_tmpdir = tempfile.mkdtemp()
    sync_colpkg = os.path.join(sync_tmpdir, "collection-2026-03-01.colpkg")
    ready_data = b"ready-flow-content!!"
    with open(sync_colpkg, "wb") as f:
        f.write(ready_data)

    import anki_backup

    original_user = anki_backup.ANKI_BACKUP_USER
    original_password = anki_backup.ANKI_BACKUP_PASSWORD
    original_url = anki_backup.ANKI_BACKUP_API_URL
    anki_backup.ANKI_BACKUP_USER = "alice"
    anki_backup.ANKI_BACKUP_PASSWORD = "password"
    anki_backup.ANKI_BACKUP_API_URL = f"http://127.0.0.1:{port}"

    uploaded_parts.clear()

    def mock_export():
        tmpd = tempfile.mkdtemp()
        p = os.path.join(tmpd, "collection-2026-03-01.colpkg")
        with open(p, "wb") as f:
            f.write(ready_data)
        return p

    def mock_create_backup(self, profile_id, artifact):
        body = {"profile_id": profile_id, "artifact": artifact, "_test_mode": "ready"}
        return upload_client._request("POST", "/backups", body)

    with (
        unittest.mock.patch("anki_backup._get_profile_id", return_value="main"),
        unittest.mock.patch("anki_backup._export_colpkg", side_effect=mock_export),
        unittest.mock.patch.object(
            anki_backup.AnkiBackupClient,
            "create_backup",
            side_effect=mock_create_backup,
            autospec=True,
        ),
    ):
        anki_backup.on_sync_did_finish()

    assert len(uploaded_parts) == 2, (
        f"expected 2 uploaded parts, got {len(uploaded_parts)}"
    )
    print("[pass] on_sync_did_finish (ready path: upload + complete + cleanup)")

    # test on_sync_did_finish with skipped path
    sync_tmpdir2 = tempfile.mkdtemp()
    sync_colpkg2 = os.path.join(sync_tmpdir2, "collection-2026-03-01.colpkg")
    with open(sync_colpkg2, "wb") as f:
        f.write(b"skipped test content")

    with (
        unittest.mock.patch("anki_backup._get_profile_id", return_value="main"),
        unittest.mock.patch("anki_backup._export_colpkg", return_value=sync_colpkg2),
    ):
        anki_backup.on_sync_did_finish()

    assert not os.path.exists(sync_colpkg2), "temp colpkg not cleaned up after sync"
    print("[pass] on_sync_did_finish (skipped path with mocked Anki + cleanup)")

    # test on_sync_did_finish skips when credentials not configured
    anki_backup.ANKI_BACKUP_USER = ""
    anki_backup.ANKI_BACKUP_PASSWORD = ""
    anki_backup.on_sync_did_finish()
    print("[pass] on_sync_did_finish (no-op when credentials empty)")

    # test on_sync_did_finish handles upload failure gracefully
    anki_backup.ANKI_BACKUP_USER = "alice"
    anki_backup.ANKI_BACKUP_PASSWORD = "password"

    fail_tmpdir = tempfile.mkdtemp()
    fail_colpkg = os.path.join(fail_tmpdir, "collection-2026-03-01.colpkg")
    with open(fail_colpkg, "wb") as f:
        f.write(b"fail test content")

    def mock_create_ready(self, profile_id, artifact):
        return {
            "status": "ready",
            "backup": {"backup_id": "fail-id"},
            "upload": {
                "part_size_bytes": 1024,
                "parts": [
                    {"part_number": 1, "upload_url": "http://127.0.0.1:1/bad-url"},
                ],
            },
        }

    caught_error = []
    original_toast_error = anki_backup.toast_error

    def capture_toast_error(msg):
        caught_error.append(msg)
        original_toast_error(msg)

    with (
        unittest.mock.patch("anki_backup._get_profile_id", return_value="main"),
        unittest.mock.patch("anki_backup._export_colpkg", return_value=fail_colpkg),
        unittest.mock.patch.object(
            anki_backup.AnkiBackupClient,
            "create_backup",
            side_effect=mock_create_ready,
            autospec=True,
        ),
        unittest.mock.patch("anki_backup.toast_error", side_effect=capture_toast_error),
    ):
        anki_backup.on_sync_did_finish()

    assert len(caught_error) == 1, f"expected 1 error toast, got {len(caught_error)}"
    assert not os.path.exists(fail_colpkg), "temp colpkg not cleaned up after failure"
    print("[pass] on_sync_did_finish (upload failure shows error toast + cleanup)")

    # test _format_backup_line
    line = anki_backup._format_backup_line(
        {
            "created_at": "2026-03-01T10:00:00Z",
            "profile_id": "main",
            "size_bytes": 534773760,
        }
    )
    assert "2026-03-01T10:00:00Z" in line, f"missing created_at in: {line}"
    assert "main" in line, f"missing profile_id in: {line}"
    assert "509.9 MB" in line or "510.0 MB" in line, f"missing size in: {line}"
    print("[pass] _format_backup_line (formats backup display line)")

    # test _get_download_url
    anki_backup.ANKI_BACKUP_USER = "alice"
    anki_backup.ANKI_BACKUP_PASSWORD = "password"
    anki_backup.ANKI_BACKUP_API_URL = f"http://127.0.0.1:{port}"

    dl_client = AnkiBackupClient(f"http://127.0.0.1:{port}", "alice", "password")
    url, expires = anki_backup._get_download_url(dl_client, "backup-001")
    assert "backup-001" in url, f"download URL missing backup id: {url}"
    assert expires == "2026-03-01T11:00:00Z", f"unexpected expires_at: {expires}"
    print("[pass] _get_download_url (fetches download URL and expiry)")

    # test show_backup_history with no credentials
    anki_backup.ANKI_BACKUP_USER = ""
    anki_backup.ANKI_BACKUP_PASSWORD = ""
    error_toasts = []
    with unittest.mock.patch(
        "anki_backup.toast_error", side_effect=lambda m: error_toasts.append(m)
    ):
        anki_backup.show_backup_history()
    assert len(error_toasts) == 1
    assert "credentials not configured" in error_toasts[0]
    print("[pass] show_backup_history (no-op when credentials empty)")

    # test show_backup_history with backups (mocks Qt dialog)
    anki_backup.ANKI_BACKUP_USER = "alice"
    anki_backup.ANKI_BACKUP_PASSWORD = "password"

    dialog_backups = []

    def mock_show_dialog(client, backups):
        dialog_backups.extend(backups)

    with unittest.mock.patch(
        "anki_backup._show_backup_list_dialog", side_effect=mock_show_dialog
    ):
        anki_backup.show_backup_history()

    assert len(dialog_backups) == 2
    assert dialog_backups[0]["backup_id"] == "backup-001"
    assert dialog_backups[1]["backup_id"] == "backup-002"
    print("[pass] show_backup_history (fetches backups and opens dialog)")

    # test get_backup success via client
    result = test_client.get_backup("backup-001")
    assert result["backup"]["download_url"] is not None
    assert result["backup"]["download_url_expires_at"] == "2026-03-01T11:00:00Z"
    print("[pass] get_backup API call (returns download URL)")

    anki_backup.ANKI_BACKUP_USER = original_user
    anki_backup.ANKI_BACKUP_PASSWORD = original_password
    anki_backup.ANKI_BACKUP_API_URL = original_url

    server.shutdown()
    print("\n=== all smoke tests passed ===")
    sys.exit(0)
