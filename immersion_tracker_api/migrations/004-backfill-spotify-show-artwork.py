#!/usr/bin/env python3

import base64
import os
import sys

import boto3
import requests

DYNAMODB_TABLE_NAME = "immersion_tracker"
USER_PREFIX = "USER#"
SPOTIFYSHOW_PREFIX = "SPOTIFYSHOW#"
SPOTIFY_TOKEN_URL = "https://accounts.spotify.com/api/token"
SPOTIFY_API_BASE = "https://api.spotify.com/v1"


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


def get_show_artwork_url(access_token, show_id):
    response = requests.get(
        f"{SPOTIFY_API_BASE}/shows/{show_id}",
        headers={"Authorization": f"Bearer {access_token}"},
        params={"market": "US"},
        timeout=30,
    )
    response.raise_for_status()
    data = response.json()

    images = data.get("images", [])
    if images and images[0].get("url"):
        return images[0]["url"]

    return None


def main():
    dry_run = "--execute" not in sys.argv

    if dry_run:
        print("DRY RUN mode - use --execute to write to DynamoDB\n")

    # get credentials
    aws_access_key_id = os.environ["AWS_ACCESS_KEY_ID"]
    aws_secret_access_key = os.environ["AWS_SECRET_ACCESS_KEY"]
    spotify_client_id = os.environ["SPOTIFY_CLIENT_ID"]
    spotify_client_secret = os.environ["SPOTIFY_CLIENT_SECRET"]

    # connect to dynamodb
    dynamodb = boto3.resource(
        "dynamodb",
        region_name="ap-southeast-2",
        aws_access_key_id=aws_access_key_id,
        aws_secret_access_key=aws_secret_access_key,
    )
    table = dynamodb.Table(DYNAMODB_TABLE_NAME)

    # get spotify access token
    access_token = get_spotify_access_token(spotify_client_id, spotify_client_secret)

    # scan for all items
    print(f"Scanning {DYNAMODB_TABLE_NAME} for Spotify show items...")
    response = table.scan()
    items = response["Items"]
    while "LastEvaluatedKey" in response:
        response = table.scan(ExclusiveStartKey=response["LastEvaluatedKey"])
        items.extend(response["Items"])

    # filter for spotify show items
    show_items = [
        item for item in items if item.get("sk", "").startswith(SPOTIFYSHOW_PREFIX)
    ]
    print(f"Found {len(show_items)} Spotify show items\n")

    stats = {
        "updated": 0,
        "already_set": 0,
        "missing_artwork": 0,
        "errored": 0,
    }

    for show in show_items:
        pk = show["pk"]
        sk = show["sk"]
        show_id = show.get("spotify_show_id")
        show_name = show.get("spotify_show_name", "Unknown")
        existing_artwork = show.get("spotify_show_artwork_url")

        descriptor = f"{show_name} ({show_id})"

        if existing_artwork is not None:
            stats["already_set"] += 1
            print(f"[SKIP] {descriptor} already has artwork URL")
            continue

        if show_id is None:
            stats["errored"] += 1
            print(f"[ERROR] {descriptor} missing show ID")
            continue

        try:
            artwork_url = get_show_artwork_url(access_token, show_id)
        except Exception as exc:
            stats["errored"] += 1
            print(f"[ERROR] Failed to fetch artwork for {descriptor}: {exc}")
            continue

        if artwork_url is None:
            stats["missing_artwork"] += 1
            print(f"[SKIP] {descriptor} returned no artwork from Spotify API")
            continue

        if dry_run:
            print(f"[DRY RUN] Would set artwork URL for {descriptor}")
        else:
            table.update_item(
                Key={"pk": pk, "sk": sk},
                UpdateExpression="SET spotify_show_artwork_url = :artwork",
                ExpressionAttributeValues={":artwork": artwork_url},
            )
            print(f"[UPDATE] Set artwork URL for {descriptor}")

        stats["updated"] += 1

    action = "Would update" if dry_run else "Updated"
    print(
        f"\n{action} {stats['updated']} shows "
        f"({stats['already_set']} already set, "
        f"{stats['missing_artwork']} missing artwork, "
        f"{stats['errored']} errors)"
    )


if __name__ == "__main__":
    main()
