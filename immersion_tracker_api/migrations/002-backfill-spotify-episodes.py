#!/usr/bin/env python3

import argparse
import base64
import datetime
import os
import sys

import boto3
import requests

DYNAMODB_REGION = "ap-southeast-2"
DYNAMODB_TABLE_NAME = "immersion_tracker"
USER_PREFIX = "USER#"
SPOTIFYEPISODE_PREFIX = "SPOTIFYEPISODE#"
SPOTIFYSHOW_PREFIX = "SPOTIFYSHOW#"
SPOTIFY_TOKEN_URL = "https://accounts.spotify.com/api/token"
SPOTIFY_API_BASE = "https://api.spotify.com/v1"


def parse_args():
    parser = argparse.ArgumentParser(
        description="Backfill Spotify episodes watched before a given episode."
    )
    parser.add_argument(
        "--episode-id",
        required=True,
        help="Spotify episode ID of the most recent watched episode (inclusive)",
    )
    parser.add_argument("--user", required=True, help="User to backfill episodes for")
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Write changes to DynamoDB (default: dry run)",
    )
    return parser.parse_args()


def get_env(name):
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(f"Missing required environment variable: {name}")
    return value


def get_spotify_access_token(client_id, client_secret):
    credentials = f"{client_id}:{client_secret}".encode()
    encoded = base64.b64encode(credentials).decode()

    response = requests.post(
        SPOTIFY_TOKEN_URL,
        data={"grant_type": "client_credentials"},
        headers={
            "Authorization": f"Basic {encoded}",
            "Content-Type": "application/x-www-form-urlencoded",
        },
        timeout=30,
    )
    response.raise_for_status()
    body = response.json()
    token = body.get("access_token")
    if not token:
        raise RuntimeError("Spotify token response missing access_token")
    return token


def get_spotify_episode(access_token, episode_id):
    response = requests.get(
        f"{SPOTIFY_API_BASE}/episodes/{episode_id}",
        headers={"Authorization": f"Bearer {access_token}"},
        params={"market": "US"},
        timeout=30,
    )
    response.raise_for_status()
    return response.json()


def get_show_episodes(access_token, show_id):
    next_url = f"{SPOTIFY_API_BASE}/shows/{show_id}/episodes"
    params = {"limit": 50, "market": "US"}
    headers = {"Authorization": f"Bearer {access_token}"}
    episodes = []

    while next_url:
        response = requests.get(next_url, headers=headers, params=params, timeout=30)
        response.raise_for_status()
        data = response.json()
        episodes.extend(data.get("items", []))
        next_url = data.get("next")
        params = None  # subsequent requests already encoded in the next URL

    return episodes


def parse_release_date(episode):
    release_date = episode.get("release_date")
    if not release_date:
        return None

    precision = episode.get("release_date_precision", "day")

    if precision == "day":
        return datetime.date.fromisoformat(release_date)

    # fallback: truncate to date if more precise values appear
    try:
        return datetime.date.fromisoformat(release_date[:10])
    except ValueError:
        return None


def duration_seconds(duration_ms):
    if duration_ms is None:
        return None
    return int(duration_ms / 1000)


def timestamp_now():
    return int(datetime.datetime.now(datetime.timezone.utc).timestamp())


def episode_exists(table, user, episode_id):
    response = table.get_item(
        Key={
            "pk": f"{USER_PREFIX}{user}",
            "sk": f"{SPOTIFYEPISODE_PREFIX}{episode_id}",
        }
    )
    return "Item" in response


def show_exists(table, user, show_id):
    response = table.get_item(
        Key={"pk": f"{USER_PREFIX}{user}", "sk": f"{SPOTIFYSHOW_PREFIX}{show_id}"}
    )
    return "Item" in response


def create_episode_item(user, show_id, episode):
    duration = duration_seconds(episode.get("duration_ms"))
    if duration is None:
        raise RuntimeError(f"Episode {episode.get('id')} missing duration_ms")

    return {
        "pk": f"{USER_PREFIX}{user}",
        "sk": f"{SPOTIFYEPISODE_PREFIX}{episode['id']}",
        "user": user,
        "spotify_episode_id": episode["id"],
        "spotify_episode_title": episode.get("name"),
        "spotify_show_id": show_id,
        "spotify_episode_duration": duration,
        "timestamp": timestamp_now(),
    }


def create_show_item(user, show_id, show_name):
    return {
        "pk": f"{USER_PREFIX}{user}",
        "sk": f"{SPOTIFYSHOW_PREFIX}{show_id}",
        "user": user,
        "spotify_show_id": show_id,
        "spotify_show_name": show_name,
    }


def main():
    args = parse_args()
    dry_run = not args.execute

    if dry_run:
        print("DRY RUN mode - use --execute to write to DynamoDB\n")

    aws_access_key_id = get_env("AWS_ACCESS_KEY_ID")
    aws_secret_access_key = get_env("AWS_SECRET_ACCESS_KEY")
    spotify_client_id = get_env("SPOTIFY_CLIENT_ID")
    spotify_client_secret = get_env("SPOTIFY_CLIENT_SECRET")

    dynamodb = boto3.resource(
        "dynamodb",
        region_name=DYNAMODB_REGION,
        aws_access_key_id=aws_access_key_id,
        aws_secret_access_key=aws_secret_access_key,
    )
    table = dynamodb.Table(DYNAMODB_TABLE_NAME)

    access_token = get_spotify_access_token(spotify_client_id, spotify_client_secret)
    target_episode = get_spotify_episode(access_token, args.episode_id)
    target_release_date = parse_release_date(target_episode)
    if target_release_date is None:
        raise RuntimeError("Could not parse release date for target episode")

    show = target_episode.get("show") or {}
    show_id = show.get("id")
    show_name = show.get("name") or "Unknown show"
    if not show_id:
        raise RuntimeError("Target episode missing show ID")

    print(f"Target show: {show_name} ({show_id})")
    print(f"Target episode release date: {target_release_date}")

    if show_exists(table, args.user, show_id):
        print("Spotify show already exists for this user")
    else:
        if dry_run:
            print(
                f"[DRY RUN] Would create Spotify show item for {show_name} ({show_id})"
            )
        else:
            table.put_item(Item=create_show_item(args.user, show_id, show_name))
            print(f"[CREATE] Spotify show item created for {show_name} ({show_id})")

    print("Fetching show episodes from Spotify...")
    episodes = get_show_episodes(access_token, show_id)
    print(f"Fetched {len(episodes)} total episodes")

    stats = {
        "considered": 0,
        "already_present": 0,
        "added": 0,
        "skipped_missing_release": 0,
    }

    for episode in episodes:
        release_date = parse_release_date(episode)
        if release_date is None:
            stats["skipped_missing_release"] += 1
            print(f"[SKIP] {episode.get('id')} missing release date")
            continue

        if release_date > target_release_date:
            continue

        stats["considered"] += 1

        episode_id = episode.get("id")
        if episode_exists(table, args.user, episode_id):
            stats["already_present"] += 1
            continue

        item = create_episode_item(args.user, show_id, episode)

        if dry_run:
            print(
                f"[DRY RUN] Would insert episode {episode_id} "
                f"({episode.get('name')}) released {release_date}"
            )
        else:
            table.put_item(Item=item)
            print(
                f"[INSERT] Added episode {episode_id} "
                f"({episode.get('name')}) released {release_date}"
            )

        stats["added"] += 1

    print(
        "\n"
        f"Episodes considered: {stats['considered']}\n"
        f"Already present: {stats['already_present']}\n"
        f"Added: {stats['added']}\n"
        f"Skipped (missing release date): {stats['skipped_missing_release']}"
    )


if __name__ == "__main__":
    main()
