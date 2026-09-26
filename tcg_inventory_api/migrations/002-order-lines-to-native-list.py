#!/usr/bin/env python3

import argparse
import json
import os

import boto3

DYNAMODB_REGION = "ap-southeast-2"
DYNAMODB_TABLE_NAME = "tcg_inventory"
ORDER_PREFIX = "ORDER#"


def parse_args():
    parser = argparse.ArgumentParser(
        description="Convert order lines from JSON strings to DynamoDB lists of maps. "
        "Pause order traffic and jobs until the migrated application is deployed."
    )
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Write changes to DynamoDB (default: dry run)",
    )
    return parser.parse_args()


def scan_orders(client):
    scan_kwargs = {
        "TableName": DYNAMODB_TABLE_NAME,
        "FilterExpression": "begins_with(#sk, :order_prefix)",
        "ProjectionExpression": "#pk, #sk, #lines",
        "ExpressionAttributeNames": {"#pk": "pk", "#sk": "sk", "#lines": "lines"},
        "ExpressionAttributeValues": {":order_prefix": {"S": ORDER_PREFIX}},
    }
    while True:
        response = client.scan(**scan_kwargs)
        yield from response.get("Items", [])
        last_evaluated_key = response.get("LastEvaluatedKey")
        if not last_evaluated_key:
            break
        scan_kwargs["ExclusiveStartKey"] = last_evaluated_key


def to_native_lines(lines_json):
    lines = json.loads(lines_json)
    if not isinstance(lines, list):
        raise ValueError("order lines must be a JSON array")

    native_lines = []
    for index, line in enumerate(lines):
        if not isinstance(line, dict):
            raise ValueError(f"order line {index} must be a JSON object")

        allowed_keys = {
            "sku_id",
            "fetchtcg_listing_id",
            "quantity",
            "price",
            "listed_price",
            "allocated_sequence_numbers",
        }
        required_keys = {
            "sku_id",
            "fetchtcg_listing_id",
            "quantity",
            "allocated_sequence_numbers",
        }
        if not required_keys.issubset(line) or not set(line).issubset(allowed_keys):
            raise ValueError(f"order line {index} has unexpected or missing fields")
        if not isinstance(line["sku_id"], str):
            raise ValueError(f"order line {index} sku_id must be a string")
        if type(line["fetchtcg_listing_id"]) is not int:
            raise ValueError(
                f"order line {index} fetchtcg_listing_id must be an integer"
            )
        if type(line["quantity"]) is not int:
            raise ValueError(f"order line {index} quantity must be an integer")
        if not isinstance(line["allocated_sequence_numbers"], list) or any(
            type(sequence_number) is not int
            for sequence_number in line["allocated_sequence_numbers"]
        ):
            raise ValueError(
                f"order line {index} allocated_sequence_numbers must be integers"
            )

        attributes = {
            "sku_id": {"S": line["sku_id"]},
            "fetchtcg_listing_id": {"N": str(line["fetchtcg_listing_id"])},
            "quantity": {"N": str(line["quantity"])},
            "allocated_sequence_numbers": {
                "L": [
                    {"N": str(sequence_number)}
                    for sequence_number in line["allocated_sequence_numbers"]
                ]
            },
        }
        for price_field in ("price", "listed_price"):
            price = line.get(price_field)
            if price is not None:
                if not isinstance(price, str):
                    raise ValueError(
                        f"order line {index} {price_field} must be a string or null"
                    )
                attributes[price_field] = {"S": price}
        native_lines.append({"M": attributes})

    return {"L": native_lines}


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
        order_key = {"pk": item["pk"], "sk": item["sk"]}
        if "lines" not in item:
            raise ValueError(
                f"order {item['pk']['S']} / {item['sk']['S']} has no lines attribute"
            )
        if "L" in item["lines"]:
            stats["already_migrated"] += 1
            continue
        if "S" not in item["lines"]:
            raise ValueError(
                f"order {item['pk']['S']} / {item['sk']['S']} lines has unexpected DynamoDB type"
            )

        native_lines = to_native_lines(item["lines"]["S"])
        stats["to_migrate"] += 1
        if not args.execute:
            print(
                f"[DRY RUN] Would convert {item['pk']['S']} / {item['sk']['S']} "
                f"({len(native_lines['L'])} lines)"
            )
            continue

        client.update_item(
            TableName=DYNAMODB_TABLE_NAME,
            Key=order_key,
            UpdateExpression="SET #lines = :native_lines",
            ConditionExpression="#lines = :old_lines",
            ExpressionAttributeNames={"#lines": "lines"},
            ExpressionAttributeValues={
                ":native_lines": native_lines,
                ":old_lines": item["lines"],
            },
        )
        print(f"[UPDATE] Converted {item['pk']['S']} / {item['sk']['S']}")

    print(
        f"Orders scanned: {stats['scanned']}\n"
        f"Already migrated: {stats['already_migrated']}\n"
        f"{'Migrated' if args.execute else 'Would migrate'}: {stats['to_migrate']}"
    )


if __name__ == "__main__":
    main()
