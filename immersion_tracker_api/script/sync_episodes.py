import os
import requests
import json
import urllib.parse
import pathlib


def main():
    local_episodes_watched = find_local_episodes_watched()
    if not len(local_episodes_watched):
        print("No local episodes watched, exiting...")
        return

    sync_local_episodes_watched(local_episodes_watched)
    update_remote_shows()
    get_remote_show_progress()

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
        print(f"{episodes_watched} episodes of {name}.")

    episodes_watched_today = res["episodes_watched_today"]
    total_hours_watched = res["total_hours_watched"]

    print()
    print(f"{episodes_watched_today} episodes watched today.")
    print(f"{total_hours_watched} total hours watched.")


def send_request(method, path, body=None):
    user = os.getenv("IMMERSION_TRACKER_USER")
    if not user:
        raise Exception("IMMERSION_TRACKER_USER is not set.")

    password = os.getenv("IMMERSION_TRACKER_PASSWORD")
    if not password:
        raise Exception("IMMERSION_TRACKER_PASSWORD is not set.")

    base_path = (
        os.getenv("IMMERSION_TRACKER_API_URL")
        or "https://9tpnqbuz76.execute-api.ap-southeast-2.amazonaws.com/prod"
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
