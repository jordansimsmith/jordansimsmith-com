#!/usr/bin/env python3

import argparse
import os

import boto3

DYNAMODB_REGION = "ap-southeast-2"
DYNAMODB_TABLE_NAME = "tcg_inventory"
USER_PREFIX = "USER#"
SKU_DELIMITER = "#SKU#"
UNIT_PREFIX = "UNIT#"
UNITS_SUFFIX = "UNITS"


def parse_args():
    parser = argparse.ArgumentParser(
        description="Backfill gsi3pk onto existing unit items so the units-by-sequence "
        "index (gsi3) covers inventory created before the index existed."
    )
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


def user_from_pk(pk):
    if not pk.startswith(USER_PREFIX) or SKU_DELIMITER not in pk:
        raise RuntimeError(f"Unexpected unit pk shape: {pk}")
    return pk.split(SKU_DELIMITER, 1)[0][len(USER_PREFIX) :]


def scan_units(table):
    scan_kwargs = {
        "FilterExpression": "begins_with(sk, :unit_prefix)",
        "ExpressionAttributeValues": {":unit_prefix": UNIT_PREFIX},
    }
    while True:
        response = table.scan(**scan_kwargs)
        yield from response.get("Items", [])
        last_evaluated_key = response.get("LastEvaluatedKey")
        if not last_evaluated_key:
            break
        scan_kwargs["ExclusiveStartKey"] = last_evaluated_key


def main():
    args = parse_args()
    dry_run = not args.execute

    if dry_run:
        print("DRY RUN mode - use --execute to write to DynamoDB\n")

    aws_access_key_id = get_env("AWS_ACCESS_KEY_ID")
    aws_secret_access_key = get_env("AWS_SECRET_ACCESS_KEY")

    dynamodb = boto3.resource(
        "dynamodb",
        region_name=DYNAMODB_REGION,
        aws_access_key_id=aws_access_key_id,
        aws_secret_access_key=aws_secret_access_key,
    )
    table = dynamodb.Table(DYNAMODB_TABLE_NAME)

    stats = {"scanned": 0, "already_backfilled": 0, "updated": 0}

    for item in scan_units(table):
        stats["scanned"] += 1
        pk = item["pk"]
        sk = item["sk"]

        if "sequence_number" not in item:
            raise RuntimeError(f"Unit {pk} / {sk} is missing sequence_number")

        gsi3pk = f"{USER_PREFIX}{user_from_pk(pk)}#{UNITS_SUFFIX}"
        if item.get("gsi3pk") == gsi3pk:
            stats["already_backfilled"] += 1
            continue

        if dry_run:
            print(f"[DRY RUN] Would set gsi3pk={gsi3pk} on {pk} / {sk}")
        else:
            table.update_item(
                Key={"pk": pk, "sk": sk},
                UpdateExpression="SET gsi3pk = :gsi3pk",
                ExpressionAttributeValues={":gsi3pk": gsi3pk},
            )
            print(f"[UPDATE] Set gsi3pk={gsi3pk} on {pk} / {sk}")

        stats["updated"] += 1

    print(
        "\n"
        f"Units scanned: {stats['scanned']}\n"
        f"Already backfilled: {stats['already_backfilled']}\n"
        f"{'Would update' if dry_run else 'Updated'}: {stats['updated']}"
    )


if __name__ == "__main__":
    main()
