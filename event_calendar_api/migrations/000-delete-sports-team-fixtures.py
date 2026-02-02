#!/usr/bin/env python3

import os
import sys
import boto3

DYNAMODB_TABLE_NAME = "event_calendar"
SPORTS_TEAM_PREFIX = "SPORTS_TEAM#"


def main():
    dry_run = "--execute" not in sys.argv

    if dry_run:
        print("DRY RUN mode - use --execute to write to DynamoDB\n")

    # get credentials
    aws_access_key_id = os.environ["AWS_ACCESS_KEY_ID"]
    aws_secret_access_key = os.environ["AWS_SECRET_ACCESS_KEY"]

    # connect to dynamodb
    dynamodb = boto3.resource(
        "dynamodb",
        region_name="ap-southeast-2",
        aws_access_key_id=aws_access_key_id,
        aws_secret_access_key=aws_secret_access_key,
    )
    table = dynamodb.Table(DYNAMODB_TABLE_NAME)

    # scan for all items
    print(f"Scanning {DYNAMODB_TABLE_NAME} for sports team fixtures...")
    response = table.scan()
    items = response["Items"]
    while "LastEvaluatedKey" in response:
        response = table.scan(ExclusiveStartKey=response["LastEvaluatedKey"])
        items.extend(response["Items"])

    # filter for sports team items
    sports_team_items = [
        item for item in items if item.get("pk", "").startswith(SPORTS_TEAM_PREFIX)
    ]
    print(f"Found {len(sports_team_items)} sports team fixture items\n")

    deleted_count = 0

    for item in sports_team_items:
        pk = item["pk"]
        sk = item["sk"]
        title = item.get("title", "Unknown")

        if dry_run:
            print(f"[DRY RUN] Would delete: {title} (pk={pk}, sk={sk})")
        else:
            table.delete_item(Key={"pk": pk, "sk": sk})
            print(f"[DELETE] Deleted: {title} (pk={pk}, sk={sk})")

        deleted_count += 1

    action = "Would delete" if dry_run else "Deleted"
    print(f"\n{action} {deleted_count} sports team fixture items")


if __name__ == "__main__":
    main()
