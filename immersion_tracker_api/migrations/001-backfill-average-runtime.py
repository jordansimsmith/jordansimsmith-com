#!/usr/bin/env python3

import os
import sys
import boto3
import requests


DYNAMODB_TABLE_NAME = "immersion_tracker"
SHOW_PREFIX = "SHOW#"
USER_PREFIX = "USER#"
TVDB_API_BASE = "https://api4.thetvdb.com/v4"


def get_tvdb_token(api_key):
    response = requests.post(
        f"{TVDB_API_BASE}/login", json={"apikey": api_key}, timeout=30
    )
    response.raise_for_status()
    body = response.json()
    if body.get("status") != "success":
        raise RuntimeError(f"TVDB login failed with status {body.get('status')}")
    token = body.get("data", {}).get("token")
    if not token:
        raise RuntimeError("TVDB login response did not include a token")
    return token


def get_average_runtime_minutes(token, series_id):
    headers = {
        "Accept": "application/json",
        "Authorization": f"Bearer {token}",
    }
    response = requests.get(
        f"{TVDB_API_BASE}/series/{series_id}", headers=headers, timeout=30
    )
    response.raise_for_status()
    body = response.json()
    if body.get("status") != "success":
        raise RuntimeError(
            f"TVDB series request failed with status {body.get('status')}"
        )
    data = body.get("data", {})
    return data.get("averageRuntime")


def main():
    dry_run = "--execute" not in sys.argv
    if dry_run:
        print("DRY RUN mode - use --execute to write to DynamoDB\n")

    aws_access_key_id = os.environ["AWS_ACCESS_KEY_ID"]
    aws_secret_access_key = os.environ["AWS_SECRET_ACCESS_KEY"]
    tvdb_api_key = os.environ["TVDB_API_KEY"]

    dynamodb = boto3.resource(
        "dynamodb",
        region_name="ap-southeast-2",
        aws_access_key_id=aws_access_key_id,
        aws_secret_access_key=aws_secret_access_key,
    )
    table = dynamodb.Table(DYNAMODB_TABLE_NAME)

    token = get_tvdb_token(tvdb_api_key)

    print(f"Scanning {DYNAMODB_TABLE_NAME} for show items...")
    response = table.scan()
    items = response["Items"]
    while "LastEvaluatedKey" in response:
        response = table.scan(ExclusiveStartKey=response["LastEvaluatedKey"])
        items.extend(response["Items"])

    show_items = [item for item in items if item.get("sk", "").startswith(SHOW_PREFIX)]
    print(f"Found {len(show_items)} show items\n")

    stats = {
        "updated": 0,
        "already_set": 0,
        "missing_tvdb_id": 0,
        "missing_runtime": 0,
        "errored": 0,
    }

    for show in show_items:
        pk = show["pk"]
        sk = show["sk"]
        folder_name = show.get("folder_name")
        user = show.get("user")
        tvdb_id = show.get("tvdb_id")
        existing_runtime = show.get("tvdb_average_runtime")

        descriptor = f"{user or pk}:{folder_name or sk}"

        if existing_runtime is not None:
            stats["already_set"] += 1
            print(f"[SKIP] {descriptor} already has tvdb_average_runtime")
            continue

        if tvdb_id is None:
            stats["missing_tvdb_id"] += 1
            print(f"[SKIP] {descriptor} missing tvdb_id")
            continue

        try:
            runtime_minutes = get_average_runtime_minutes(token, int(tvdb_id))
        except Exception as exc:
            stats["errored"] += 1
            print(f"[ERROR] Failed to fetch runtime for {descriptor}: {exc}")
            continue

        if runtime_minutes is None:
            stats["missing_runtime"] += 1
            print(f"[SKIP] {descriptor} returned no runtime from TVDB")
            continue

        runtime_seconds = int(runtime_minutes) * 60

        if dry_run:
            print(
                f"[DRY RUN] Would set tvdb_average_runtime={runtime_seconds}s for {descriptor}"
            )
        else:
            table.update_item(
                Key={"pk": pk, "sk": sk},
                UpdateExpression="SET tvdb_average_runtime = :runtime",
                ExpressionAttributeValues={":runtime": runtime_seconds},
            )
            print(
                f"[UPDATE] Set tvdb_average_runtime={runtime_seconds}s for {descriptor}"
            )

        stats["updated"] += 1

    action = "Would update" if dry_run else "Updated"
    print(
        f"\n{action} {stats['updated']} shows "
        f"({stats['already_set']} already set, "
        f"{stats['missing_tvdb_id']} missing tvdb_id, "
        f"{stats['missing_runtime']} missing runtime, "
        f"{stats['errored']} errors)"
    )


if __name__ == "__main__":
    main()
