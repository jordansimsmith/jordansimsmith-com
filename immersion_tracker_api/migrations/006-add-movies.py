#!/usr/bin/env python3

import argparse
import os
from datetime import date, datetime, time
from zoneinfo import ZoneInfo

import boto3
import requests

DYNAMODB_TABLE_NAME = "immersion_tracker"
DYNAMODB_REGION = "ap-southeast-2"
USER_PREFIX = "USER#"
MOVIE_PREFIX = "MOVIE#"
MANUAL_FILE_PREFIX = "manual_tmdb_"
TMDB_API_BASE = "https://api.themoviedb.org/3"
TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
ZONE_ID = ZoneInfo("Pacific/Auckland")


def parse_args():
    parser = argparse.ArgumentParser(
        description="Add watched movies without corresponding local files."
    )
    parser.add_argument("--user", required=True)
    parser.add_argument("--tmdb-id", type=int, action="append", required=True)
    parser.add_argument(
        "--watched-on",
        type=date.fromisoformat,
        help="watch date in YYYY-MM-DD format (defaults to today in Pacific/Auckland)",
    )
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()

    if any(tmdb_id <= 0 for tmdb_id in args.tmdb_id):
        parser.error("--tmdb-id values must be positive")

    return args


def get_tmdb_movie(access_token, movie_id):
    response = requests.get(
        f"{TMDB_API_BASE}/movie/{movie_id}",
        headers={
            "Accept": "application/json",
            "Authorization": f"Bearer {access_token}",
        },
        timeout=30,
    )
    response.raise_for_status()
    movie = response.json()

    if movie.get("id") != movie_id:
        raise RuntimeError(f"Expected TMDB movie ID {movie_id}, got {movie.get('id')}")

    title = movie.get("title")
    if not title or not title.strip():
        raise RuntimeError("TMDB movie response missing title")

    runtime_minutes = movie.get("runtime")
    if not isinstance(runtime_minutes, int) or runtime_minutes <= 0:
        raise RuntimeError("TMDB movie response missing a positive runtime")

    poster_path = movie.get("poster_path")
    image = f"{TMDB_IMAGE_BASE}{poster_path}" if poster_path else None
    return {
        "id": movie_id,
        "name": title,
        "image": image,
        "duration_seconds": runtime_minutes * 60,
    }


def find_existing_tmdb_ids(table, user):
    expression_values = {
        ":pk": f"{USER_PREFIX}{user}",
        ":movie_prefix": MOVIE_PREFIX,
    }
    response = table.query(
        KeyConditionExpression="pk = :pk AND begins_with(sk, :movie_prefix)",
        ExpressionAttributeValues=expression_values,
        ProjectionExpression="tmdb_id",
    )
    items = response["Items"]
    while "LastEvaluatedKey" in response:
        response = table.query(
            KeyConditionExpression="pk = :pk AND begins_with(sk, :movie_prefix)",
            ExpressionAttributeValues=expression_values,
            ProjectionExpression="tmdb_id",
            ExclusiveStartKey=response["LastEvaluatedKey"],
        )
        items.extend(response["Items"])
    return {int(item["tmdb_id"]) for item in items if item.get("tmdb_id") is not None}


def create_movie_item(user, movie, watched_on):
    watched_at = datetime.combine(watched_on, time(hour=12), tzinfo=ZONE_ID)
    file_name = f"{MANUAL_FILE_PREFIX}{movie['id']}"
    item = {
        "pk": f"{USER_PREFIX}{user}",
        "sk": f"{MOVIE_PREFIX}{file_name}",
        "user": user,
        "file_name": file_name,
        "tmdb_id": movie["id"],
        "tmdb_name": movie["name"],
        "movie_duration": movie["duration_seconds"],
        "timestamp": int(watched_at.timestamp()),
    }
    if movie["image"]:
        item["tmdb_image"] = movie["image"]
    return item


def main():
    args = parse_args()
    dry_run = not args.execute
    if dry_run:
        print("DRY RUN mode - use --execute to write to DynamoDB\n")

    aws_access_key_id = os.environ["AWS_ACCESS_KEY_ID"]
    aws_secret_access_key = os.environ["AWS_SECRET_ACCESS_KEY"]
    tmdb_access_token = os.environ["TMDB_API_READ_ACCESS_TOKEN"]

    dynamodb = boto3.resource(
        "dynamodb",
        region_name=DYNAMODB_REGION,
        aws_access_key_id=aws_access_key_id,
        aws_secret_access_key=aws_secret_access_key,
    )
    table = dynamodb.Table(DYNAMODB_TABLE_NAME)

    watched_on = args.watched_on or datetime.now(ZONE_ID).date()
    existing_tmdb_ids = find_existing_tmdb_ids(table, args.user)
    stats = {"added": 0, "already_present": 0, "errored": 0}

    for tmdb_id in args.tmdb_id:
        if tmdb_id in existing_tmdb_ids:
            stats["already_present"] += 1
            print(f"[SKIP] TMDB movie {tmdb_id} is already present for {args.user}")
            continue

        try:
            movie = get_tmdb_movie(tmdb_access_token, tmdb_id)
        except Exception as exc:
            stats["errored"] += 1
            print(f"[ERROR] Failed to fetch TMDB movie {tmdb_id}: {exc}")
            continue

        item = create_movie_item(args.user, movie, watched_on)
        if dry_run:
            print(
                f"[DRY RUN] Would add {movie['name']} ({movie['id']}) "
                f"watched on {watched_on}"
            )
        else:
            table.put_item(Item=item)
            print(
                f"[ADD] Added {movie['name']} ({movie['id']}) watched on {watched_on}"
            )

        existing_tmdb_ids.add(tmdb_id)
        stats["added"] += 1

    action = "Would add" if dry_run else "Added"
    print(
        f"\n{action} {stats['added']} movies "
        f"({stats['already_present']} already present, {stats['errored']} errors)"
    )


if __name__ == "__main__":
    main()
