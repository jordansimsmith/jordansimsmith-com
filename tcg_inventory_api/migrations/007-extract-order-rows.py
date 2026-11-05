#!/usr/bin/env python3

import argparse

import boto3


def parse_args():
    parser = argparse.ArgumentParser(
        description="Extract Order rows. Pause order traffic and workers until migration "
        "and application cutover finish. Retain the legacy lines attribute."
    )
    parser.add_argument("--table-name", default="tcg_inventory")
    parser.add_argument("--region", default="ap-southeast-2")
    parser.add_argument(
        "--execute", action="store_true", help="Write changes (default: dry run)"
    )
    return parser.parse_args()


def scan_items(client, table):
    request = {"TableName": table, "ConsistentRead": True}
    items = {}
    page = 0
    while True:
        response = client.scan(**request)
        page += 1
        batch = response.get("Items", [])
        for item in batch:
            items[(item["pk"]["S"], item["sk"]["S"])] = item
        print(f"Scan page {page}: {len(batch)} items ({len(items)} total)", flush=True)
        continuation = response.get("LastEvaluatedKey")
        if not continuation:
            return items
        request["ExclusiveStartKey"] = continuation


def build_plan(items):
    plans = []
    completed = 0
    for (pk, sk), order in items.items():
        if not pk.startswith("USER#") or not sk.startswith("ORDER#"):
            continue
        user = pk[len("USER#") :]
        order_id = order["order_id"]["S"]
        row_pk = f"{pk}#ORDER#{order_id}"
        if "row_count" in order:
            count = int(order["row_count"]["N"])
            if count < 0:
                raise ValueError(f"Invalid row count: {pk} / {sk}")
            for position in range(count):
                row = items.get((row_pk, f"ROW#{position:010d}"))
                if row is None or row.get("position") != {"N": str(position)}:
                    raise ValueError(f"Incomplete committed rows: {pk} / {sk}")
            completed += 1
            continue
        source = order["lines"]
        if set(source) != {"L"}:
            raise ValueError(f"Expected native line list: {pk} / {sk}")
        rows = []
        for position, line in enumerate(source["L"]):
            fields = line["M"]
            for required in (
                "sku_id",
                "fetchtcg_listing_id",
                "quantity",
                "allocated_sequence_numbers",
            ):
                if required not in fields:
                    raise ValueError(f"Missing {required}: {pk} / {sk} / {position}")
            row = dict(fields)
            row.update(
                pk={"S": row_pk},
                sk={"S": f"ROW#{position:010d}"},
                user={"S": user},
                order_id={"S": order_id},
                position={"N": str(position)},
            )
            existing = items.get((row_pk, row["sk"]["S"]))
            if existing is not None and existing != row:
                raise ValueError(
                    f"Conflicting destination: {row_pk} / {row['sk']['S']}"
                )
            rows.append(row)
        plans.append((order, rows))
    return plans, completed


def migrate_order(client, table, order, rows):
    for row in rows:
        client.put_item(TableName=table, Item=row)
    for row in rows:
        saved = client.get_item(
            TableName=table, Key={k: row[k] for k in ("pk", "sk")}, ConsistentRead=True
        ).get("Item")
        if saved != row:
            raise RuntimeError(
                f"Order row readback mismatch: {row['pk']} / {row['sk']}"
            )
    key = {k: order[k] for k in ("pk", "sk")}
    client.update_item(
        TableName=table,
        Key=key,
        UpdateExpression="SET row_count = :count",
        ConditionExpression="#lines = :source AND attribute_not_exists(row_count)",
        ExpressionAttributeNames={"#lines": "lines"},
        ExpressionAttributeValues={
            ":count": {"N": str(len(rows))},
            ":source": order["lines"],
        },
    )
    saved = client.get_item(TableName=table, Key=key, ConsistentRead=True).get("Item")
    expected = dict(order, row_count={"N": str(len(rows))})
    if saved != expected:
        raise RuntimeError(f"Order parent readback mismatch: {key}")


def main():
    args = parse_args()
    client = boto3.client("dynamodb", region_name=args.region)
    print(
        f"{'EXECUTE' if args.execute else 'DRY RUN'}: scanning {args.table_name} in {args.region}",
        flush=True,
    )
    plans, completed = build_plan(scan_items(client, args.table_name))
    row_count = sum(len(rows) for _, rows in plans)
    print(
        f"Already complete: {completed}; orders to migrate: {len(plans)}; rows: {row_count}",
        flush=True,
    )
    for index, (order, rows) in enumerate(plans, start=1):
        action = "Migrating" if args.execute else "Would migrate"
        print(
            f"{action} {order['pk']['S']} / {order['sk']['S']}: {len(rows)} rows ({index}/{len(plans)})",
            flush=True,
        )
        if args.execute:
            migrate_order(client, args.table_name, order, rows)
    print(
        f"{'Migrated' if args.execute else 'Would migrate'} {len(plans)} orders and {row_count} rows; legacy lines retained",
        flush=True,
    )


if __name__ == "__main__":
    main()
