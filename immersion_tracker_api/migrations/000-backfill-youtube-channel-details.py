#!/usr/bin/env python3

import os
import sys
import boto3
import requests

DYNAMODB_TABLE_NAME = "immersion_tracker"
YOUTUBEVIDEO_PREFIX = "YOUTUBEVIDEO#"
YOUTUBECHANNEL_PREFIX = "YOUTUBECHANNEL#"
USER_PREFIX = "USER#"
YOUTUBE_API_BASE = "https://www.googleapis.com/youtube/v3"


def get_channel_title_from_api(channel_id, api_key):
    response = requests.get(
        f"{YOUTUBE_API_BASE}/channels",
        params={"part": "snippet", "id": channel_id, "key": api_key},
    )
    response.raise_for_status()
    data = response.json()
    return data["items"][0]["snippet"]["title"]


def get_channel_from_video_api(video_id, api_key):
    response = requests.get(
        f"{YOUTUBE_API_BASE}/videos",
        params={"part": "snippet", "id": video_id, "key": api_key},
    )
    response.raise_for_status()
    data = response.json()
    snippet = data["items"][0]["snippet"]
    return snippet["channelId"], snippet["channelTitle"]


def channel_exists(table, user, channel_id):
    response = table.get_item(
        Key={
            "pk": f"{USER_PREFIX}{user}",
            "sk": f"{YOUTUBECHANNEL_PREFIX}{channel_id}",
        }
    )
    return "Item" in response


def create_channel_item(table, user, channel_id, channel_title, dry_run):
    item = {
        "pk": f"{USER_PREFIX}{user}",
        "sk": f"{YOUTUBECHANNEL_PREFIX}{channel_id}",
        "user": user,
        "youtube_channel_id": channel_id,
        "youtube_channel_title": channel_title,
    }

    if dry_run:
        print(f"  [DRY RUN] Would create: {channel_title} ({channel_id})")
    else:
        table.put_item(Item=item)
        print(f"  Created: {channel_title} ({channel_id})")


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
    print(f"Scanning {DYNAMODB_TABLE_NAME}...")
    response = table.scan()
    items = response["Items"]
    while "LastEvaluatedKey" in response:
        response = table.scan(ExclusiveStartKey=response["LastEvaluatedKey"])
        items.extend(response["Items"])

    # filter for video items
    video_items = [
        item for item in items if item.get("sk", "").startswith(YOUTUBEVIDEO_PREFIX)
    ]
    print(f"Found {len(video_items)} video items\n")

    # track processed channels to avoid duplicates
    processed_channels = set()
    channels_created = 0

    for video_item in video_items:
        user = video_item["user"]
        video_id = video_item["youtube_video_id"]
        channel_id = video_item.get("youtube_channel_id")

        print(f"Processing {video_id} (user: {user})")

        # get channel_id and channel_title
        if channel_id:
            channel_title = get_channel_title_from_api(channel_id, youtube_api_key)
        else:
            print(f"  Fetching channel from video API...")
            channel_id, channel_title = get_channel_from_video_api(
                video_id, youtube_api_key
            )

        # skip if already processed in this run
        channel_key = f"{user}#{channel_id}"
        if channel_key in processed_channels:
            print(f"  Skipping - already processed {channel_id}")
            continue

        processed_channels.add(channel_key)

        # skip if channel already exists in database
        if channel_exists(table, user, channel_id):
            print(f"  Skipping - channel already exists")
            continue

        # create channel item
        create_channel_item(table, user, channel_id, channel_title, dry_run)
        channels_created += 1

    print(
        f"\n{'Would create' if dry_run else 'Created'} {channels_created} channel items"
    )


if __name__ == "__main__":
    main()
