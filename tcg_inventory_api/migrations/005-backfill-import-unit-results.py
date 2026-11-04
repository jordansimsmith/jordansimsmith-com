#!/usr/bin/env python3

import argparse

import boto3

REGION = "ap-southeast-2"
TABLE = "tcg_inventory"


def parse_args():
    parser = argparse.ArgumentParser(
        description=(
            "Backfill saved SKU IDs and locations on confirmed import rows. "
            "Drain legacy import-confirmation jobs and pause writes before executing."
        )
    )
    parser.add_argument("--table-name", default=TABLE)
    parser.add_argument("--region", default=REGION)
    parser.add_argument(
        "--execute", action="store_true", help="Write changes (default: dry run)"
    )
    return parser.parse_args()


def string_value(item, name):
    value = item.get(name)
    if value is None:
        return None
    if set(value) == {"NULL"} and value["NULL"]:
        return None
    if set(value) != {"S"}:
        raise ValueError(f"Invalid {name} attribute")
    return value["S"]


def number_value(item, name):
    value = item.get(name)
    if value is None:
        return None
    if set(value) == {"NULL"} and value["NULL"]:
        return None
    if set(value) != {"N"}:
        raise ValueError(f"Invalid {name} attribute")
    return int(value["N"])


def user_and_import_id_from_row(item):
    pk = string_value(item, "pk")
    if not pk.startswith("USER#") or "#IMPORT#" not in pk:
        raise ValueError(f"Invalid import row partition: {pk}")
    user, import_id = pk[len("USER#") :].split("#IMPORT#", 1)
    if not user or not import_id:
        raise ValueError(f"Invalid import row partition: {pk}")
    return user, import_id


def location_for_sequence(sequence_number):
    if sequence_number < 0:
        raise ValueError("Confirmed import row has a negative sequence number")
    block_number, offset = divmod(sequence_number, 100)
    letter = chr(ord("A") + block_number // 100)
    return f"{letter}{block_number % 100}-{offset}"


def user_and_sku_id_from_unit_pk(pk):
    if not pk.startswith("USER#") or "#SKU#" not in pk:
        raise ValueError(f"Invalid inventory unit partition: {pk}")
    user, sku_id = pk[len("USER#") :].split("#SKU#", 1)
    if not user or not sku_id:
        raise ValueError(f"Invalid inventory unit partition: {pk}")
    return user, sku_id


def scan_items(client, table):
    request = {"TableName": table, "ConsistentRead": True}
    page = 0
    scanned = 0
    parents = {}
    rows = []
    unit_skus = {}
    while True:
        response = client.scan(**request)
        items = response.get("Items", [])
        page += 1
        scanned += len(items)
        print(f"Scan page {page}: {len(items)} items ({scanned} total)", flush=True)
        for item in items:
            pk = string_value(item, "pk")
            sk = string_value(item, "sk")
            if sk.startswith("UNIT#"):
                user, sku_id = user_and_sku_id_from_unit_pk(pk)
                game = string_value(item, "game")
                sequence_number = number_value(item, "sequence_number")
                if game is None or sequence_number is None:
                    raise ValueError(
                        f"Inventory unit is missing its game or sequence: {pk} / {sk}"
                    )
                key = (user, game, sequence_number)
                existing = unit_skus.get(key)
                if existing is not None and existing != sku_id:
                    raise ValueError(f"Conflicting inventory units for sequence: {key}")
                unit_skus[key] = sku_id
            elif sk.startswith("IMPORT#") and pk.startswith("USER#"):
                user = pk[len("USER#") :]
                import_id = sk[len("IMPORT#") :]
                if "#" in user or not user or not import_id:
                    raise ValueError(f"Invalid import key: {pk} / {sk}")
                parents[(user, import_id)] = (
                    string_value(item, "status"),
                    string_value(item, "game"),
                )
            elif sk.startswith("ROW#") and "#IMPORT#" in pk:
                rows.append(item)
        continuation = response.get("LastEvaluatedKey")
        if not continuation:
            return parents, rows, unit_skus, scanned
        request["ExclusiveStartKey"] = continuation


def build_plan(parents, rows, unit_skus):
    if any(status == "confirming" for status, _ in parents.values()):
        raise RuntimeError("A legacy import confirmation is still active")

    updates = []
    already_complete = 0
    for row in rows:
        if string_value(row, "decision") != "keep":
            continue
        user, import_id = user_and_import_id_from_row(row)
        parent = parents.get((user, import_id))
        if parent is None or parent[0] != "confirmed":
            continue

        sequence_number = number_value(row, "sequence_number")
        if sequence_number is None:
            raise ValueError(f"Confirmed keep row is missing sequence_number: {row}")
        location = location_for_sequence(sequence_number)
        saved_sku_id = string_value(row, "sku_id")
        saved_location = string_value(row, "location")
        if saved_sku_id is not None and saved_location is not None:
            if saved_location != location:
                raise ValueError(
                    f"Saved location conflicts with sequence number: {row}"
                )
            already_complete += 1
            continue

        sku_id = unit_skus.get((user, parent[1], sequence_number))
        if sku_id is None:
            raise ValueError(
                "No inventory unit found for confirmed row "
                f"{user} / {import_id} / {sequence_number}"
            )
        if saved_sku_id is not None and saved_sku_id != sku_id:
            raise ValueError(f"Saved SKU ID conflicts with Inventory unit: {row}")
        if saved_location is not None and saved_location != location:
            raise ValueError(f"Saved location conflicts with sequence number: {row}")
        updates.append((row, sku_id, location))
    return updates, already_complete


def update_row(client, table, row, sku_id, location):
    client.update_item(
        TableName=table,
        Key={name: row[name] for name in ("pk", "sk")},
        UpdateExpression="SET #sku_id = :sku_id, #location = :location",
        ConditionExpression=(
            "attribute_exists(pk) AND "
            "(attribute_not_exists(#sku_id) OR attribute_type(#sku_id, :null_type) "
            "OR #sku_id = :sku_id) AND "
            "(attribute_not_exists(#location) OR attribute_type(#location, :null_type) "
            "OR #location = :location)"
        ),
        ExpressionAttributeNames={"#sku_id": "sku_id", "#location": "location"},
        ExpressionAttributeValues={
            ":sku_id": {"S": sku_id},
            ":location": {"S": location},
            ":null_type": {"S": "NULL"},
        },
    )
    saved = client.get_item(
        TableName=table,
        Key={name: row[name] for name in ("pk", "sk")},
        ConsistentRead=True,
    ).get("Item")
    if saved is None:
        raise RuntimeError("Import row backfill readback was missing")
    if (
        string_value(saved, "sku_id") != sku_id
        or string_value(saved, "location") != location
    ):
        raise RuntimeError("Import row backfill readback mismatch")


def main():
    args = parse_args()
    client = boto3.client("dynamodb", region_name=args.region)
    print(
        f"{'EXECUTE' if args.execute else 'DRY RUN'}: scanning "
        f"{args.table_name} in {args.region}",
        flush=True,
    )
    parents, rows, unit_skus, scanned = scan_items(client, args.table_name)
    updates, already_complete = build_plan(parents, rows, unit_skus)
    print(
        f"Scanned items: {scanned}; import rows: {len(rows)}; "
        f"already complete: {already_complete}; "
        f"{'would backfill' if not args.execute else 'backfilling'}: {len(updates)}",
        flush=True,
    )

    for index, (row, sku_id, location) in enumerate(updates, start=1):
        user, import_id = user_and_import_id_from_row(row)
        position = number_value(row, "position")
        action = "Backfilling" if args.execute else "Would backfill"
        print(
            f"{action} {user} / {import_id} / row {position} ({index}/{len(updates)})",
            flush=True,
        )
        if args.execute:
            update_row(client, args.table_name, row, sku_id, location)


if __name__ == "__main__":
    main()
