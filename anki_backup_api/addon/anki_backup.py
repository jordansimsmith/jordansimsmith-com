import hashlib
import json
import os
import tempfile
import time
import urllib.parse
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests
from aqt import gui_hooks, mw
from aqt.operations import QueryOp
from aqt.qt import QAction
from aqt.utils import tooltip

DEFAULT_ANKI_BACKUP_API_URL = "https://api.anki-backup.jordansimsmith.com"
REQUEST_TIMEOUT_SECONDS = 60


def log(message):
    print(f"[anki-backup] {message}", flush=True)


def _format_duration(seconds):
    if seconds < 1:
        return f"{seconds * 1000:.0f}ms"
    return f"{seconds:.2f}s"


def _format_size(num_bytes):
    return f"{num_bytes / (1024 * 1024):.2f} MB"


def _format_throughput(num_bytes, seconds):
    if seconds <= 0:
        return "n/a"
    return f"{(num_bytes / (1024 * 1024)) / seconds:.2f} MB/s"


def send_request(method, path, body=None):
    user = os.getenv("ANKI_BACKUP_USER")
    if not user:
        raise Exception("ANKI_BACKUP_USER is not set.")

    password = os.getenv("ANKI_BACKUP_PASSWORD")
    if not password:
        raise Exception("ANKI_BACKUP_PASSWORD is not set.")

    base_path = os.getenv("ANKI_BACKUP_API_URL") or DEFAULT_ANKI_BACKUP_API_URL
    if base_path[-1] != "/":
        base_path += "/"
    if path[0] == "/":
        path = path[1:]
    url = urllib.parse.urljoin(base_path, path)

    headers = {
        "Content-Type": "application/json;charset=UTF-8",
        "Accept": "application/json;charset=UTF-8",
    }
    data = json.dumps(body).encode("utf-8") if body is not None else None

    response = requests.request(
        method,
        url,
        data=data,
        headers=headers,
        auth=(user, password),
        timeout=REQUEST_TIMEOUT_SECONDS,
    )
    if response.status_code not in (200, 201):
        raise Exception(
            f"{method} {path} request failed with code {response.status_code} and body {response.text}"
        )

    return response.json()


def create_backup(profile_id, artifact):
    return send_request(
        "POST", "/backups", {"profile_id": profile_id, "artifact": artifact}
    )


def update_backup(backup_id, status):
    return send_request("PUT", f"/backups/{backup_id}", {"status": status})


def describe_artifact(path):
    filename = os.path.basename(path)
    size_bytes = os.path.getsize(path)
    hash_start = time.monotonic()
    sha = hashlib.sha256()
    with open(path, "rb") as file:
        for chunk in iter(lambda: file.read(8192), b""):
            sha.update(chunk)
    hash_duration = time.monotonic() - hash_start
    log(
        f"Hashed {_format_size(size_bytes)} in {_format_duration(hash_duration)} "
        f"({_format_throughput(size_bytes, hash_duration)})"
    )

    return {
        "filename": filename,
        "size_bytes": size_bytes,
        "sha256": sha.hexdigest(),
    }


def export_colpkg(col):
    log("Creating collection export package...")
    export_start = time.monotonic()
    temp_dir = tempfile.mkdtemp()
    filename = f"collection-{uuid.uuid4()}.colpkg"
    out_path = os.path.join(temp_dir, filename)
    col.export_collection_package(out_path, include_media=True, legacy=False)
    export_duration = time.monotonic() - export_start
    size_bytes = os.path.getsize(out_path)
    log(
        f"Collection export package created "
        f"({_format_size(size_bytes)} in {_format_duration(export_duration)})"
    )

    return out_path


def upload_part(part, data):
    start = time.monotonic()
    response = requests.put(
        part["upload_url"],
        data=data,
        timeout=REQUEST_TIMEOUT_SECONDS,
    )
    duration = time.monotonic() - start
    if response.status_code != 200:
        raise Exception(
            f"failed to upload part {part['part_number']} with code {response.status_code} and body {response.text}"
        )
    log(
        f"Uploaded part {part['part_number']} "
        f"({_format_size(len(data))} in {_format_duration(duration)} = "
        f"{_format_throughput(len(data), duration)})"
    )


def upload_and_complete(colpkg_path, backup_id, upload):
    part_size = upload["part_size_bytes"]
    parts = upload["parts"]

    read_start = time.monotonic()
    part_data = []
    with open(colpkg_path, "rb") as file:
        for part in parts:
            part_data.append((part, file.read(part_size)))
    read_duration = time.monotonic() - read_start
    total_bytes = sum(len(data) for _, data in part_data)
    log(
        f"Read {len(parts)} part(s) ({_format_size(total_bytes)}) "
        f"into memory in {_format_duration(read_duration)}"
    )

    upload_start = time.monotonic()
    with ThreadPoolExecutor() as executor:
        futures = [executor.submit(upload_part, part, data) for part, data in part_data]
        for future in as_completed(futures):
            future.result()
    upload_duration = time.monotonic() - upload_start
    log(
        f"Uploaded {len(parts)} part(s) ({_format_size(total_bytes)}) in "
        f"{_format_duration(upload_duration)} "
        f"(aggregate {_format_throughput(total_bytes, upload_duration)})"
    )

    log("Finalizing backup...")
    finalize_start = time.monotonic()
    update_backup(backup_id, "COMPLETED")
    finalize_duration = time.monotonic() - finalize_start
    log(f"Backup finalized in {_format_duration(finalize_duration)}")


def run_backup(col, profile_id):
    colpkg_path = None
    backup_start = time.monotonic()
    try:
        if not os.getenv("ANKI_BACKUP_USER"):
            raise Exception("ANKI_BACKUP_USER is not set.")

        if not os.getenv("ANKI_BACKUP_PASSWORD"):
            raise Exception("ANKI_BACKUP_PASSWORD is not set.")

        colpkg_path = export_colpkg(col)
        artifact = describe_artifact(colpkg_path)

        log("Creating backup record...")
        create_start = time.monotonic()
        result = create_backup(profile_id, artifact)
        create_duration = time.monotonic() - create_start
        log(f"Backup record created in {_format_duration(create_duration)}")

        if result["status"] == "skipped":
            log("Backup skipped by server (already backed up recently).")
            return "skipped"

        backup_id = result["backup"]["backup_id"]
        upload = result["upload"]
        log(f"Uploading {len(upload['parts'])} part(s) to S3...")
        upload_and_complete(colpkg_path, backup_id, upload)
        total_duration = time.monotonic() - backup_start
        log(f"Backup completed successfully in {_format_duration(total_duration)}")

        return "completed"
    finally:
        if colpkg_path is not None:
            try:
                os.unlink(colpkg_path)
                os.rmdir(os.path.dirname(colpkg_path))
            except OSError:
                pass


def on_manual_backup_action_triggered():
    profile_id = mw.pm.name
    log(f"Manual backup triggered for profile '{profile_id}'.")

    gui_hooks.collection_will_temporarily_close(mw.col)

    def reopen_collection_if_needed():
        if mw.col.db is not None:
            return True

        log("Reopening collection...")
        try:
            mw.reopen()
            return True
        except Exception as error:
            log(f"Backup failed while reopening collection: {error}")
            mw.close()
            return False

    def on_success(status):
        if not reopen_collection_if_needed():
            return

        if status == "skipped":
            tooltip("Backup: backup skipped (already backed up recently)", parent=mw)
            return

        tooltip("Backup: backup completed successfully", parent=mw)

    def on_failure(error):
        if not reopen_collection_if_needed():
            return

        log(f"Backup failed: {error}")
        tooltip(f"Backup error: {error}", parent=mw)

    QueryOp(
        parent=mw,
        op=lambda col: run_backup(col, profile_id),
        success=on_success,
    ).with_progress("Backing up collection...").failure(on_failure).run_in_background()


def register_menu_action():
    backup_action = QAction("Run backup now", mw)
    backup_action.triggered.connect(on_manual_backup_action_triggered)
    mw.form.menuTools.addAction(backup_action)
