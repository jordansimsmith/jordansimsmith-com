#!/usr/bin/env python3

import os
import sys

import boto3
import requests

DYNAMODB_TABLE_NAME = "immersion_tracker"
MOVIE_PREFIX = "MOVIE#"
TMDB_API_BASE = "https://api.themoviedb.org/3"


def get_original_title(access_token, movie_id):
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

    original_title = movie.get("original_title")
    if not original_title or not original_title.strip():
        raise RuntimeError("TMDB movie response missing original_title")
    return original_title


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
        key=lambda item: (item.get("user", ""), item.get("tmdb_name", "")),
    )
    print(f"Found {len(movie_items)} movie items\n")

    stats = {
        "updated": 0,
        "unchanged": 0,
        "missing_tmdb_id": 0,
        "errored": 0,
    }

    for movie_item in movie_items:
        pk = movie_item["pk"]
        sk = movie_item["sk"]
        current_title = movie_item.get("tmdb_name")
        tmdb_id = movie_item.get("tmdb_id")
        descriptor = f"{current_title or sk} ({tmdb_id or 'no TMDB ID'})"

        if tmdb_id is None:
            stats["missing_tmdb_id"] += 1
            print(f"[SKIP] {descriptor}")
            continue

        try:
            original_title = get_original_title(tmdb_access_token, int(tmdb_id))
        except Exception as exc:
            stats["errored"] += 1
            print(f"[ERROR] Failed to fetch original title for {descriptor}: {exc}")
            continue

        if current_title == original_title:
            stats["unchanged"] += 1
            print(f"[SKIP] {descriptor} already uses the original title")
            continue

        if dry_run:
            print(f"[DRY RUN] Would rename {current_title!r} to {original_title!r}")
        else:
            table.update_item(
                Key={"pk": pk, "sk": sk},
                UpdateExpression="SET tmdb_name = :tmdb_name",
                ExpressionAttributeValues={":tmdb_name": original_title},
            )
            print(f"[UPDATE] Renamed {current_title!r} to {original_title!r}")
        stats["updated"] += 1

    action = "Would update" if dry_run else "Updated"
    print(
        f"\n{action} {stats['updated']} movies "
        f"({stats['unchanged']} unchanged, "
        f"{stats['missing_tmdb_id']} missing TMDB ID, "
        f"{stats['errored']} errors)"
    )


if __name__ == "__main__":
    main()
