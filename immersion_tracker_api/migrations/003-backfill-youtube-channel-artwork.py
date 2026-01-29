#!/usr/bin/env python3

import os
import sys
import boto3
import requests

DYNAMODB_TABLE_NAME = "immersion_tracker"
USER_PREFIX = "USER#"
YOUTUBECHANNEL_PREFIX = "YOUTUBECHANNEL#"
YOUTUBE_API_BASE = "https://www.googleapis.com/youtube/v3"


def get_channel_artwork_url(channel_id, api_key):
    response = requests.get(
        f"{YOUTUBE_API_BASE}/channels",
        params={"part": "snippet", "id": channel_id, "key": api_key},
    )
    response.raise_for_status()
    data = response.json()

    if not data.get("items"):
        return None

    thumbnails = data["items"][0].get("snippet", {}).get("thumbnails", {})

    # pick best available thumbnail in order of preference
    for size in ["maxres", "standard", "high", "medium", "default"]:
        if size in thumbnails and thumbnails[size].get("url"):
            return thumbnails[size]["url"]

    return None


def main():
    dry_run = "--execute" not in sys.argv

    if dry_run:
        print("DRY RUN mode - use --execute to write to DynamoDB\n")

    # get credentials
    aws_access_key_id = os.environ["AWS_ACCESS_KEY_ID"]
    aws_secret_access_key = os.environ["AWS_SECRET_ACCESS_KEY"]
    youtube_api_key = os.environ["YOUTUBE_API_KEY"]

    # connect to dynamodb
    dynamodb = boto3.resource(
        "dynamodb",
        region_name="ap-southeast-2",
        aws_access_key_id=aws_access_key_id,
        aws_secret_access_key=aws_secret_access_key,
    )
    table = dynamodb.Table(DYNAMODB_TABLE_NAME)

    # scan for all items
    print(f"Scanning {DYNAMODB_TABLE_NAME} for YouTube channel items...")
    response = table.scan()
    items = response["Items"]
    while "LastEvaluatedKey" in response:
        response = table.scan(ExclusiveStartKey=response["LastEvaluatedKey"])
        items.extend(response["Items"])

    # filter for youtube channel items
    channel_items = [
        item for item in items if item.get("sk", "").startswith(YOUTUBECHANNEL_PREFIX)
    ]
    print(f"Found {len(channel_items)} YouTube channel items\n")

    stats = {
        "updated": 0,
        "already_set": 0,
        "missing_artwork": 0,
        "errored": 0,
    }

    for channel in channel_items:
        pk = channel["pk"]
        sk = channel["sk"]
        channel_id = channel.get("youtube_channel_id")
        channel_title = channel.get("youtube_channel_title", "Unknown")
        existing_artwork = channel.get("youtube_channel_artwork_url")

        descriptor = f"{channel_title} ({channel_id})"

        if existing_artwork is not None:
            stats["already_set"] += 1
            print(f"[SKIP] {descriptor} already has artwork URL")
            continue

        if channel_id is None:
            stats["errored"] += 1
            print(f"[ERROR] {descriptor} missing channel ID")
            continue

        try:
            artwork_url = get_channel_artwork_url(channel_id, youtube_api_key)
        except Exception as exc:
            stats["errored"] += 1
            print(f"[ERROR] Failed to fetch artwork for {descriptor}: {exc}")
            continue

        if artwork_url is None:
            stats["missing_artwork"] += 1
            print(f"[SKIP] {descriptor} returned no artwork from YouTube API")
            continue

        if dry_run:
            print(f"[DRY RUN] Would set artwork URL for {descriptor}")
        else:
            table.update_item(
                Key={"pk": pk, "sk": sk},
                UpdateExpression="SET youtube_channel_artwork_url = :artwork",
                ExpressionAttributeValues={":artwork": artwork_url},
            )
            print(f"[UPDATE] Set artwork URL for {descriptor}")

        stats["updated"] += 1

    action = "Would update" if dry_run else "Updated"
    print(
        f"\n{action} {stats['updated']} channels "
        f"({stats['already_set']} already set, "
        f"{stats['missing_artwork']} missing artwork, "
        f"{stats['errored']} errors)"
    )


if __name__ == "__main__":
    main()
