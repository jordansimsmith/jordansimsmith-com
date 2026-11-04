#!/usr/bin/env python3

import argparse

import boto3

REGION = "ap-southeast-2"
TABLE = "tcg_inventory"


def parse_args():
    parser = argparse.ArgumentParser(
        description=(
            "Rename Inventory unit order_id to reservation_id. "
            "Pause application processing and writes before executing; deploy the "
            "updated readers and writers before resuming."
        )
    )
    parser.add_argument("--table-name", default=TABLE)
    parser.add_argument("--region", default=REGION)
    parser.add_argument(
        "--execute", action="store_true", help="Write changes (default: dry run)"
    )
    return parser.parse_args()


def scan_units(client, table):
    request = {"TableName": table, "ConsistentRead": True}
    updates = []
    scanned = 0
    page = 0
    while True:
        response = client.scan(**request)
        items = response.get("Items", [])
        scanned += len(items)
        page += 1
        print(f"Scan page {page}: {len(items)} items ({scanned} total)", flush=True)
        for item in items:
            pk = item["pk"]["S"]
            sk = item["sk"]["S"]
            if not (
                pk.startswith("USER#") and "#SKU#" in pk and sk.startswith("UNIT#")
            ):
                continue
            if "order_id" not in item:
                continue
            owner = item["order_id"]
            if set(owner) != {"S"} and owner != {"NULL": True}:
                raise ValueError(f"Invalid unit owner: {pk} / {sk}")
            if "reservation_id" in item and item["reservation_id"] != owner:
                raise ValueError(f"Conflicting unit ownership: {pk} / {sk}")
            updates.append(item)
        continuation = response.get("LastEvaluatedKey")
        if not continuation:
            return updates, scanned
        request["ExclusiveStartKey"] = continuation


def update_unit(client, table, item):
    key = {name: item[name] for name in ("pk", "sk")}
    owner = item["order_id"]
    client.update_item(
        TableName=table,
        Key=key,
        UpdateExpression="SET reservation_id = :owner REMOVE order_id",
        ConditionExpression=(
            "attribute_exists(pk) AND order_id = :owner AND "
            "(attribute_not_exists(reservation_id) OR reservation_id = :owner)"
        ),
        ExpressionAttributeValues={":owner": owner},
    )
    saved = client.get_item(TableName=table, Key=key, ConsistentRead=True).get("Item")
    if saved is None or "order_id" in saved or saved.get("reservation_id") != owner:
        raise RuntimeError(f"Unit ownership readback mismatch: {key}")


def main():
    args = parse_args()
    client = boto3.client("dynamodb", region_name=args.region)
    print(
        f"{'EXECUTE' if args.execute else 'DRY RUN'}: scanning "
        f"{args.table_name} in {args.region}",
        flush=True,
    )
    updates, scanned = scan_units(client, args.table_name)
    print(f"Scanned items: {scanned}; units to rename: {len(updates)}", flush=True)
    for index, item in enumerate(updates, start=1):
        action = "Renaming" if args.execute else "Would rename"
        print(
            f"{action} {item['pk']['S']} / {item['sk']['S']} ({index}/{len(updates)})",
            flush=True,
        )
        if args.execute:
            update_unit(client, args.table_name, item)
    print(
        f"{'Renamed' if args.execute else 'Would rename'} {len(updates)} units",
        flush=True,
    )


if __name__ == "__main__":
    main()
