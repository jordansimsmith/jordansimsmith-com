#!/usr/bin/env python3

import os
import sys

import boto3
import requests

DYNAMODB_TABLE_NAME = "immersion_tracker"
MOVIE_PREFIX = "MOVIE#"
TMDB_API_BASE = "https://api.themoviedb.org/3"
TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"


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

    title = movie.get("original_title")
    if not title or not title.strip():
        raise RuntimeError("TMDB movie response missing original_title")

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


def main():
    dry_run = "--execute" not in sys.argv
    if dry_run:
        print("DRY RUN mode - use --execute to write to DynamoDB\n")

    aws_access_key_id = os.environ["AWS_ACCESS_KEY_ID"]
    aws_secret_access_key = os.environ["AWS_SECRET_ACCESS_KEY"]
    tmdb_access_token = os.environ["TMDB_API_READ_ACCESS_TOKEN"]

    dynamodb = boto3.resource(
        "dynamodb",
        region_name="ap-southeast-2",
        aws_access_key_id=aws_access_key_id,
        aws_secret_access_key=aws_secret_access_key,
    )
    table = dynamodb.Table(DYNAMODB_TABLE_NAME)

    print(f"Scanning {DYNAMODB_TABLE_NAME} for movie items...")
    response = table.scan()
    items = response["Items"]
    while "LastEvaluatedKey" in response:
        response = table.scan(ExclusiveStartKey=response["LastEvaluatedKey"])
        items.extend(response["Items"])

    movie_items = sorted(
        (item for item in items if item.get("sk", "").startswith(MOVIE_PREFIX)),
        key=lambda item: (
            item.get("user", ""),
            item.get("tvdb_name") or item.get("tmdb_name") or item.get("file_name", ""),
        ),
    )
    print(f"Found {len(movie_items)} movie items")

    stats = {
        "updated": 0,
        "already_migrated": 0,
        "skipped": 0,
        "declined": 0,
        "errored": 0,
    }

    for movie_item in movie_items:
        if movie_item.get("tmdb_id") is not None:
            stats["already_migrated"] += 1
            print(
                f"[SKIP] {movie_item.get('tmdb_name', movie_item['sk'])} "
                "already has TMDB metadata"
            )
            continue

        pk = movie_item["pk"]
        sk = movie_item["sk"]
        user = movie_item.get("user", pk)
        file_name = movie_item.get("file_name", sk.removeprefix(MOVIE_PREFIX))
        title = movie_item.get("tvdb_name") or file_name

        print(f"\nMovie: {title}")
        print(f"File: {file_name}")
        print(f"User: {user}")
        raw_tmdb_id = input("Enter the equivalent TMDB id (blank to skip):\n").strip()
        if not raw_tmdb_id:
            stats["skipped"] += 1
            print(f"[SKIP] Left {title} unchanged")
            continue

        try:
            tmdb_id = int(raw_tmdb_id)
            if tmdb_id <= 0:
                raise ValueError("TMDB ID must be positive")
            tmdb_movie = get_tmdb_movie(tmdb_access_token, tmdb_id)
        except Exception as exc:
            stats["errored"] += 1
            print(f"[ERROR] Failed to resolve TMDB movie for {title}: {exc}")
            continue

        print(f"TMDB match: {tmdb_movie['name']} ({tmdb_movie['id']})")
        print(f"Runtime: {tmdb_movie['duration_seconds'] // 60} minutes")
        print(f"Artwork: {tmdb_movie['image'] or 'none'}")
        confirmed = input("Use this TMDB movie? [y/N]\n").strip().lower()
        if confirmed != "y":
            stats["declined"] += 1
            print(f"[SKIP] Left {title} unchanged")
            continue

        if dry_run:
            print(f"[DRY RUN] Would migrate {title} to {tmdb_movie['name']}")
        else:
            expression_values = {
                ":tmdb_id": tmdb_movie["id"],
                ":tmdb_name": tmdb_movie["name"],
                ":movie_duration": tmdb_movie["duration_seconds"],
            }
            if tmdb_movie["image"]:
                expression_values[":tmdb_image"] = tmdb_movie["image"]
                update_expression = (
                    "SET tmdb_id = :tmdb_id, tmdb_name = :tmdb_name, "
                    "tmdb_image = :tmdb_image, movie_duration = :movie_duration "
                    "REMOVE tvdb_id, tvdb_name, tvdb_image"
                )
            else:
                update_expression = (
                    "SET tmdb_id = :tmdb_id, tmdb_name = :tmdb_name, "
                    "movie_duration = :movie_duration "
                    "REMOVE tmdb_image, tvdb_id, tvdb_name, tvdb_image"
                )

            table.update_item(
                Key={"pk": pk, "sk": sk},
                UpdateExpression=update_expression,
                ExpressionAttributeValues=expression_values,
            )
            print(f"[UPDATE] Migrated {title} to {tmdb_movie['name']}")

        stats["updated"] += 1

    action = "Would update" if dry_run else "Updated"
    print(
        f"\n{action} {stats['updated']} movies "
        f"({stats['already_migrated']} already migrated, "
        f"{stats['skipped']} skipped, "
        f"{stats['declined']} declined, "
        f"{stats['errored']} errors)"
    )


if __name__ == "__main__":
    main()
