#!/usr/bin/env python3

import argparse

import boto3


def parse_args():
    parser = argparse.ArgumentParser(
        description="Copy order tracking cutoffs from SETTINGS to ORDER_SETTINGS."
    )
    parser.add_argument("--table-name", default="tcg_inventory")
    parser.add_argument("--region", default="ap-southeast-2")
    parser.add_argument(
        "--execute", action="store_true", help="Write changes (default: dry run)"
    )
    return parser.parse_args()


def scan_settings(client, table):
    request = {"TableName": table, "ConsistentRead": True}
    settings = []
    scanned = 0
    page = 0
    while True:
        response = client.scan(**request)
        items = response.get("Items", [])
        page += 1
        scanned += len(items)
        print(f"Scan page {page}: {len(items)} items ({scanned} total)", flush=True)
        for item in items:
            if item.get("sk") != {"S": "SETTINGS"}:
                continue
            pk = item["pk"]["S"]
            if not pk.startswith("USER#"):
                raise ValueError(f"Invalid settings partition key: {pk}")
            user = pk[len("USER#") :]
            if item.get("user", {"S": user}) != {"S": user}:
                raise ValueError(f"Settings user does not match its key: {pk}")
            if "track_orders_after" not in item or item["track_orders_after"] == {
                "NULL": True
            }:
                continue
            cutoff = item["track_orders_after"]
            if set(cutoff) != {"N"}:
                raise ValueError(f"Invalid track_orders_after value: {pk}")
            int(cutoff["N"])
            settings.append((user, cutoff))
        continuation = response.get("LastEvaluatedKey")
        if not continuation:
            return settings, scanned
        request["ExclusiveStartKey"] = continuation


def destination_record(user, cutoff):
    return {
        "pk": {"S": f"USER#{user}"},
        "sk": {"S": "ORDER_SETTINGS"},
        "user": {"S": user},
        "track_orders_after": cutoff,
    }


def plan_migration(client, table, source):
    plan = []
    complete = 0
    for user, cutoff in source:
        expected = destination_record(user, cutoff)
        existing = client.get_item(
            TableName=table,
            Key={"pk": expected["pk"], "sk": expected["sk"]},
            ConsistentRead=True,
        ).get("Item")
        if existing is None:
            plan.append((user, expected))
        elif existing != expected:
            raise ValueError(f"Conflicting ORDER_SETTINGS record for user {user}")
        else:
            complete += 1
    return plan, complete


def migrate_item(client, table, user, item):
    client.put_item(
        TableName=table, Item=item, ConditionExpression="attribute_not_exists(pk)"
    )
    saved = client.get_item(
        TableName=table,
        Key={"pk": item["pk"], "sk": item["sk"]},
        ConsistentRead=True,
    ).get("Item")
    if saved != item:
        raise RuntimeError(f"ORDER_SETTINGS readback mismatch for user {user}")


def main():
    args = parse_args()
    client = boto3.client("dynamodb", region_name=args.region)
    print(
        f"{'EXECUTE' if args.execute else 'DRY RUN'}: scanning {args.table_name} in {args.region}",
        flush=True,
    )
    source, scanned = scan_settings(client, args.table_name)
    plan, complete = plan_migration(client, args.table_name, source)
    print(
        f"Scanned items: {scanned}; already migrated: {complete}; cutoffs to migrate: {len(plan)}",
        flush=True,
    )
    for index, (user, item) in enumerate(plan, start=1):
        print(
            f"{'Migrating' if args.execute else 'Would migrate'} {user} ({index}/{len(plan)})",
            flush=True,
        )
        if args.execute:
            migrate_item(client, args.table_name, user, item)
    print(
        f"{'Migrated' if args.execute else 'Would migrate'} {len(plan)} order settings",
        flush=True,
    )


if __name__ == "__main__":
    main()
