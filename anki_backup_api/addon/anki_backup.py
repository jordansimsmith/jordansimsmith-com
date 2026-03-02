import datetime
import hashlib
import json
import os
import tempfile
import urllib.parse

import requests
from aqt import gui_hooks, mw
from aqt.utils import tooltip

DEFAULT_ANKI_BACKUP_API_URL = "https://api.anki-backup.jordansimsmith.com"
REQUEST_TIMEOUT_SECONDS = 60


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
    sha = hashlib.sha256()
    with open(path, "rb") as file:
        for chunk in iter(lambda: file.read(8192), b""):
            sha.update(chunk)

    return {
        "filename": filename,
        "size_bytes": size_bytes,
        "sha256": sha.hexdigest(),
    }


def cleanup_temp_file(path):
    if path is None:
        return

    try:
        os.unlink(path)
        os.rmdir(os.path.dirname(path))
    except OSError:
        pass


def get_profile_id():
    return mw.pm.name


def export_colpkg():
    temp_dir = tempfile.mkdtemp()
    filename = f"collection-{datetime.date.today().isoformat()}.colpkg"
    out_path = os.path.join(temp_dir, filename)
    mw.col.export_collection_package(out_path, include_media=True, legacy=False)
    return out_path


def upload_and_complete(colpkg_path, backup_id, upload):
    part_size = upload["part_size_bytes"]
    parts = upload["parts"]

    with open(colpkg_path, "rb") as file:
        for part in parts:
            data = file.read(part_size)
            response = requests.put(
                part["upload_url"],
                data=data,
                timeout=REQUEST_TIMEOUT_SECONDS,
            )
            if response.status_code != 200:
                raise Exception(
                    f"failed to upload part {part['part_number']} with code {response.status_code} and body {response.text}"
                )

    update_backup(backup_id, "COMPLETED")


def on_sync_did_finish():
    colpkg_path = None
    try:
        if not os.getenv("ANKI_BACKUP_USER"):
            raise Exception("ANKI_BACKUP_USER is not set.")

        if not os.getenv("ANKI_BACKUP_PASSWORD"):
            raise Exception("ANKI_BACKUP_PASSWORD is not set.")

        profile_id = get_profile_id()
        colpkg_path = export_colpkg()
        artifact = describe_artifact(colpkg_path)
        result = create_backup(profile_id, artifact)

        if result["status"] == "skipped":
            tooltip("Backup: backup skipped (already backed up recently)", parent=mw)
            return

        backup_id = result["backup"]["backup_id"]
        upload = result["upload"]
        upload_and_complete(colpkg_path, backup_id, upload)
        tooltip("Backup: backup completed successfully", parent=mw)
    except Exception as error:
        tooltip(f"Backup error: {error}", parent=mw)
    finally:
        cleanup_temp_file(colpkg_path)


gui_hooks.sync_did_finish.append(on_sync_did_finish)
