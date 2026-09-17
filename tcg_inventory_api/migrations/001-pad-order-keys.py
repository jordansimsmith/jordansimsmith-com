#!/usr/bin/env python3

import argparse
import os

import boto3

DYNAMODB_REGION = "ap-southeast-2"
DYNAMODB_TABLE_NAME = "tcg_inventory"
ORDER_PREFIX = "ORDER#"
MAX_ORDER_ID = 9223372036854775807


def parse_args():
    parser = argparse.ArgumentParser(
        description="Move existing orders to zero-padded numeric primary sort keys. "
        "Pause order traffic and jobs until the padded-key application is deployed."
    )
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Write changes to DynamoDB (default: dry run)",
    )
    return parser.parse_args()


def format_order_sk(order_id):
    if not order_id.isascii() or not order_id.isdecimal() or order_id.startswith("0"):
        raise ValueError(f"Invalid order id: {order_id}")
    numeric_id = int(order_id)
    if numeric_id > MAX_ORDER_ID:
        raise ValueError(f"Order id exceeds Java long range: {order_id}")
    return f"{ORDER_PREFIX}{numeric_id:020d}"


def scan_orders(client):
    scan_kwargs = {
        "TableName": DYNAMODB_TABLE_NAME,
        "FilterExpression": "begins_with(sk, :order_prefix)",
        "ExpressionAttributeValues": {":order_prefix": {"S": ORDER_PREFIX}},
    }
    while True:
        response = client.scan(**scan_kwargs)
        yield from response.get("Items", [])
        last_evaluated_key = response.get("LastEvaluatedKey")
        if not last_evaluated_key:
            break
        scan_kwargs["ExclusiveStartKey"] = last_evaluated_key


def main():
    args = parse_args()
    client = boto3.client(
        "dynamodb",
        region_name=DYNAMODB_REGION,
        aws_access_key_id=os.environ["AWS_ACCESS_KEY_ID"],
        aws_secret_access_key=os.environ["AWS_SECRET_ACCESS_KEY"],
    )
    stats = {"scanned": 0, "already_migrated": 0, "to_migrate": 0}

    for item in scan_orders(client):
        stats["scanned"] += 1
        order_id = item["order_id"]["S"]
        old_sk = item["sk"]["S"]
        new_sk = format_order_sk(order_id)
        if old_sk == new_sk:
            stats["already_migrated"] += 1
            continue
        if old_sk != ORDER_PREFIX + order_id:
            raise ValueError(f"Unexpected order key {old_sk} for order {order_id}")

        stats["to_migrate"] += 1
        if not args.execute:
            print(f"[DRY RUN] Would move {item['pk']['S']} / {old_sk} to {new_sk}")
            continue

        new_item = dict(item)
        new_item["sk"] = {"S": new_sk}
        client.transact_write_items(
            TransactItems=[
                {
                    "Put": {
                        "TableName": DYNAMODB_TABLE_NAME,
                        "Item": new_item,
                        "ConditionExpression": "attribute_not_exists(pk)",
                    }
                },
                {
                    "Delete": {
                        "TableName": DYNAMODB_TABLE_NAME,
                        "Key": {"pk": item["pk"], "sk": item["sk"]},
                        "ConditionExpression": "attribute_exists(pk) AND order_id = :order_id AND updated_at = :updated_at",
                        "ExpressionAttributeValues": {
                            ":order_id": item["order_id"],
                            ":updated_at": item["updated_at"],
                        },
                    }
                },
            ]
        )
        print(f"[UPDATE] Moved {item['pk']['S']} / {old_sk} to {new_sk}")

    print(
        f"Orders scanned: {stats['scanned']}\n"
        f"Already migrated: {stats['already_migrated']}\n"
        f"{'Migrated' if args.execute else 'Would migrate'}: {stats['to_migrate']}"
    )


if __name__ == "__main__":
    main()
