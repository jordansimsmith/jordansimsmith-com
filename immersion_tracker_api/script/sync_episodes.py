import glob
import json
import os
import pathlib
import urllib.parse

import requests


def main():
    local_episodes_watched = find_local_episodes_watched()
    if not len(local_episodes_watched):
        print("No local episodes watched, exiting...")
        return

    sync_local_episodes_watched(local_episodes_watched)
    update_remote_shows()
    get_remote_show_progress()
    delete_local_episodes_watched(local_episodes_watched)

    print()
    input("Press ENTER to close...")


def find_local_episodes_watched():
    print("Finding local episodes watched...")
    episodes = []

    for show in os.listdir(os.path.curdir):
        if not os.path.isdir(show):
            continue

        watched = os.path.join(show, "watched")
        if not os.path.isdir(watched):
            continue

        for episode in os.listdir(watched):
            if not os.path.isfile(os.path.join(watched, episode)):
                continue

            episode = {"folder_name": show, "file_name": pathlib.Path(episode).stem}
            episodes.append(episode)

    return episodes


def sync_local_episodes_watched(episodes):
    print(f"Syncing {len(episodes)} local episodes watched...")

    res = send_request("POST", "sync", {"episodes": episodes})
    episodes_added = res["episodes_added"]
    print(f"Successfully added {episodes_added} new episodes to the remote server.")


def update_remote_shows():
    print("Retrieving remote show metadata...")

    res = send_request("GET", "shows")
    for show in res["shows"]:
        if show["tvdb_id"]:
            continue

        folder_name = show["folder_name"]
        tvdb_id = int(input(f"Enter the TVDB id for show {folder_name}:\n"))
        send_request("PUT", "show", {"folder_name": folder_name, "tvdb_id": tvdb_id})
        print("Successfully updated show metadata.")


def get_remote_show_progress():
    print("Retrieving progress summary...")
    print()

    res = send_request("GET", "progress")
    for show in res["shows"]:
        name = show["name"] or "Unknown"
        episodes_watched = show["episodes_watched"]
        print(f"{episodes_watched} episodes of {name}")

    episodes_watched_today = res["episodes_watched_today"]
    total_hours_watched = res["total_hours_watched"]

    print()
    print(f"{episodes_watched_today} episodes watched today.")
    print(f"{total_hours_watched} total hours watched.")


def delete_local_episodes_watched(episodes):
    print()
    print(f"Deleting {len(episodes)} local episodes watched...")

    size_bytes = 0
    for episode in episodes:
        path = os.path.join(
            episode["folder_name"], "watched", episode["file_name"] + "*"
        )
        files = glob.glob(path)

        if len(files) != 1 or not os.path.isfile(files[0]):
            raise Exception(
                f"episode at {path} for deletion did not contain a single file"
            )

        try:
            episode_size_bytes = os.path.getsize(files[0])
            os.remove(files[0])
            size_bytes += episode_size_bytes
        except OSError:
            # episode probably in use
            pass

    size_gigabytes = size_bytes / 1024 / 1024 / 1024
    print(f"Deleted {size_gigabytes:.2f} GB of watched episodes.")


def send_request(method, path, body=None):
    user = os.getenv("IMMERSION_TRACKER_USER")
    if not user:
        raise Exception("IMMERSION_TRACKER_USER is not set.")

    password = os.getenv("IMMERSION_TRACKER_PASSWORD")
    if not password:
        raise Exception("IMMERSION_TRACKER_PASSWORD is not set.")

    base_path = (
        os.getenv("IMMERSION_TRACKER_API_URL")
        or "https://api.immersion-tracker.jordansimsmith.com"
    )
    if base_path[-1] != "/":
        base_path += "/"
    if path[0] == "/":
        path = path[1:]
    url = urllib.parse.urljoin(base_path, path)

    headers = {
        "Content-Type": "application/json;charset=UTF-8",
        "Accept": "application/json;charset=UTF-8",
    }
    params = {"user": user}
    data = json.dumps(body).encode("utf-8") if body else None

    res = requests.request(
        method, url, data=data, params=params, headers=headers, auth=(user, password)
    )
    if res.status_code != 200:
        raise Exception(
            f"{method} {path} request failed with non code {res.status_code} and body {res.text}"
        )

    return res.json()


if __name__ == "__main__":
    main()
