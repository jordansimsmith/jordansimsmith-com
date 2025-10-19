import glob
import json
import os
import pathlib
import urllib.parse
import shutil
import re

import requests

SUPPORTED_EXTENSIONS = [".mkv", ".mp4"]


def main():
    local_episodes_watched = find_local_episodes_watched()
    youtube_video_ids = find_youtube_videos_watched()

    if len(local_episodes_watched):
        sync_local_episodes_watched(local_episodes_watched)
        update_remote_shows()

    if len(youtube_video_ids):
        sync_youtube_videos_watched(youtube_video_ids)

    get_remote_show_progress()

    if local_episodes_watched or youtube_video_ids:
        print()

    if len(local_episodes_watched):
        delete_local_episodes_watched(local_episodes_watched)
        delete_completed_shows(local_episodes_watched)

    if len(youtube_video_ids):
        clear_youtube_watched_file()

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

            if os.path.splitext(episode)[1] not in SUPPORTED_EXTENSIONS:
                continue

            episode = {"folder_name": show, "file_name": pathlib.Path(episode).stem}
            episodes.append(episode)

    episodes.sort(key=lambda x: (x["folder_name"], x["file_name"]))

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

    if res["shows"] and res["youtube_channels"]:
        print()

    for channel in res["youtube_channels"]:
        name = channel["channel_name"] or "Unknown"
        videos_watched = channel["videos_watched"]
        video_word = "video" if videos_watched == 1 else "videos"
        print(f"{videos_watched} {video_word} of {name}")

    if res["shows"] or res["youtube_channels"]:
        print()

    episodes_watched_today = res["episodes_watched_today"]
    youtube_videos_watched_today = res["youtube_videos_watched_today"]
    total_hours_watched = res["total_hours_watched"]

    average_days_per_month = 365 / 12
    years_since_first_episode = int(res["days_since_first_episode"] / 365)
    months_since_first_episode = int(
        (res["days_since_first_episode"] % 365) / average_days_per_month
    )

    print(f"{episodes_watched_today} episodes watched today.")
    print(f"{youtube_videos_watched_today} YouTube videos watched today.")
    print()
    display_weekly_activity(res["daily_activity"])
    print()
    print(
        f"{total_hours_watched} total hour{'' if total_hours_watched == 1 else 's'} watched."
    )

    weekly_trend_percentage = res.get("weekly_trend_percentage")
    if weekly_trend_percentage is not None:
        trend_percentage = round(weekly_trend_percentage)
        print(
            f"This week's activity is {'+' if trend_percentage > 0 else ''}{trend_percentage}% compared to the average."
        )

    print(
        f"{years_since_first_episode} year{'' if years_since_first_episode == 1 else 's'} and {months_since_first_episode} month{'' if months_since_first_episode == 1 else 's'} since immersion started."
    )


def delete_local_episodes_watched(episodes):
    print(f"Deleting {len(episodes)} local episodes watched...")

    size_bytes = 0
    for episode in episodes:
        path = os.path.join(episode["folder_name"], "watched", episode["file_name"])
        pattern = glob.escape(path) + ".*"
        files = glob.glob(pattern)

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


def delete_completed_shows(episodes):
    for episode in episodes:
        folder = episode["folder_name"]
        if not os.path.isdir(folder):
            continue

        # check that the folder is empty
        if any(
            any(pathlib.Path(folder).rglob(f"*{ext}")) for ext in SUPPORTED_EXTENSIONS
        ):
            continue

        shutil.rmtree(folder)
        print(f"Deleted completed show: {folder}")


def find_youtube_videos_watched():
    print("Finding YouTube videos watched...")
    video_ids = []

    youtube_file = "youtube_watched.txt"
    if not os.path.isfile(youtube_file):
        return video_ids

    with open(youtube_file, "r") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue

            # Extract video ID from https://youtube.com?v= format
            match = re.search(r"youtube\.com.*[?&]v=([a-zA-Z0-9_-]{11})", line)
            if not match:
                raise Exception(f"Unable to extract YouTube video ID from line: {line}")

            video_ids.append(match.group(1))

    return video_ids


def sync_youtube_videos_watched(video_ids):
    print(f"Syncing {len(video_ids)} YouTube videos watched...")

    res = send_request("POST", "syncyoutube", {"video_ids": video_ids})
    videos_added = res["videos_added"]
    print(f"Successfully added {videos_added} new YouTube videos to the remote server.")


def clear_youtube_watched_file():
    print(f"Clearing YouTube videos watched...")
    youtube_file = "youtube_watched.txt"
    if os.path.isfile(youtube_file):
        # Clear the file contents
        open(youtube_file, "w").close()


def display_weekly_activity(daily_activity):
    print("Weekly activity:")

    max_minutes = max(day["minutes_watched"] for day in daily_activity)
    if max_minutes == 0:
        max_minutes = 1

    bar_width = 30

    for day in daily_activity:
        days_ago = day["days_ago"]
        minutes = day["minutes_watched"]

        # format day label
        if days_ago == 0:
            label = "Today"
        elif days_ago == 1:
            label = "Yesterday"
        else:
            label = f"{days_ago} days ago"

        # calculate bar
        bar_length = int((minutes / max_minutes) * bar_width)
        bar = "█" * bar_length
        bar_padded = bar.ljust(bar_width)

        # format time
        if minutes == 0:
            time_str = "0m"
        else:
            hours = minutes // 60
            mins = minutes % 60
            if hours > 0:
                time_str = f"{hours}h {mins}m" if mins > 0 else f"{hours}h"
            else:
                time_str = f"{mins}m"

        # print line
        print(f"{label:<11}│{bar_padded}  {time_str:>7}")


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
