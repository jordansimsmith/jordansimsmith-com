#!/usr/bin/env python3

import argparse
import copy
import json
import re
import time
from collections import Counter, defaultdict
from dataclasses import dataclass


REGION = "ap-southeast-2"
TABLE = "tcg_inventory"
GAME = "mtg"
SOURCE = "scryfall"
FINISHES = {"normal", "foil", "etched"}
CONDITIONS = {"NM", "LP", "MP", "HP", "DMG"}
EXTERNAL_ID = re.compile(r"[A-Za-z0-9._~-]+\Z")


@dataclass
class Change:
    kind: str
    before: dict
    after: dict | None
    condition_attribute: str


@dataclass
class Plan:
    copies: list[Change]
    updates: list[Change]
    deletes: list[Change]
    reports: list[Change]
    counts: Counter
    sku_counts: dict[str, int]
    unit_counts: dict[str, int]


def value(item, name):
    attribute = item.get(name)
    if attribute is None or set(attribute) != {"S"}:
        raise ValueError(f"{item['pk']['S']} / {item['sk']['S']}: invalid {name}")
    return attribute["S"]


def number(item, name):
    attribute = item.get(name)
    if attribute is None or set(attribute) != {"N"}:
        raise ValueError(f"{item['pk']['S']} / {item['sk']['S']}: invalid {name}")
    result = int(attribute["N"])
    if result < 0:
        raise ValueError(f"{item['pk']['S']} / {item['sk']['S']}: negative {name}")
    return result


def key(item):
    return value(item, "pk"), value(item, "sk")


def item_key(item):
    pk, sk = key(item)
    return {"pk": {"S": pk}, "sk": {"S": sk}}


def new_sku_id(external_id, finish, condition):
    if not EXTERNAL_ID.fullmatch(external_id):
        raise ValueError("external id must contain only ASCII unreserved characters")
    if finish not in FINISHES or condition not in CONDITIONS:
        raise ValueError(
            f"unsupported Magic finish or condition: {finish} / {condition}"
        )
    return f"{GAME}#{SOURCE}#{external_id}#{finish}#{condition}"


def legacy_sku_id(new_id):
    parts = new_id.split("#")
    if len(parts) != 5 or parts[0:2] != [GAME, SOURCE]:
        raise ValueError(f"unexpected migrated SKU ID: {new_id}")
    if new_sku_id(parts[2], parts[3], parts[4]) != new_id:
        raise ValueError(f"invalid migrated SKU ID: {new_id}")
    return "#".join(parts[2:])


def scan_items(client, table):
    items = {}
    request = {"TableName": table, "ConsistentRead": True}
    while True:
        response = client.scan(**request)
        for item in response.get("Items", []):
            item_id = key(item)
            if item_id in items:
                raise ValueError(f"duplicate table key: {item_id}")
            items[item_id] = item
        if "LastEvaluatedKey" not in response:
            return items
        request["ExclusiveStartKey"] = response["LastEvaluatedKey"]


def build_plan(items, require_native_lines=False):
    copies = []
    updates = []
    deletes = []
    reports = []
    counts = Counter()
    sku_counts = Counter()
    unit_counts = Counter()
    sku_ids = {}
    sku_targets = {}
    logical_units = {}
    max_sequence = defaultdict(lambda: -1)
    counters = {}

    def add_move(kind, before, after, condition_attribute):
        source_key = key(before)
        target_key = key(after)
        if source_key == target_key:
            raise ValueError(f"{kind} did not change key: {source_key}")
        existing = items.get(target_key)
        if existing is None:
            copies.append(Change(kind, before, after, condition_attribute))
        elif existing != after:
            raise ValueError(f"destination collision: {target_key}")
        deletes.append(Change(kind, before, after, condition_attribute))

    def add_update(kind, before, after, condition_attribute):
        if before != after:
            updates.append(Change(kind, before, after, condition_attribute))

    for item in items.values():
        pk, sk = key(item)
        if "#SKU#" not in pk or sk != "SKU":
            continue
        user, stored_id = pk.split("#SKU#", 1)
        if not user.startswith("USER#") or not user[5:]:
            raise ValueError(f"invalid SKU partition: {pk}")
        if value(item, "sku_id") != stored_id:
            raise ValueError(f"SKU ID does not match partition: {pk}")
        finish = value(item, "finish")
        condition = value(item, "condition")
        if "scryfall_id" in item:
            if any(
                field in item for field in ("game", "external_source", "external_id")
            ):
                raise ValueError(f"mixed legacy SKU identity: {pk}")
            external_id = value(item, "scryfall_id")
            if stored_id != f"{external_id}#{finish}#{condition}":
                raise ValueError(f"legacy SKU identity mismatch: {pk}")
            if value(item, "gsi1sk") != f"SKU#{stored_id}":
                raise ValueError(f"legacy SKU dirty key mismatch: {pk}")
            target_id = new_sku_id(external_id, finish, condition)
            old_gsi2sk = value(item, "gsi2sk")
            suffix = "#" + stored_id
            if not old_gsi2sk.startswith("NAME#") or not old_gsi2sk.endswith(suffix):
                raise ValueError(f"legacy SKU browse key mismatch: {pk}")
            normalized_name = old_gsi2sk[5 : -len(suffix)]
            after = copy.deepcopy(item)
            after["pk"] = {"S": f"{user}#SKU#{target_id}"}
            after["sku_id"] = {"S": target_id}
            after["game"] = {"S": GAME}
            after["external_source"] = {"S": SOURCE}
            after["external_id"] = {"S": external_id}
            after["gsi1sk"] = {"S": f"SKU#{target_id}"}
            after["gsi2sk"] = {"S": f"GAME#{GAME}#NAME#{normalized_name}#{target_id}"}
            del after["scryfall_id"]
            add_move("sku", item, after, "sku_id")
        else:
            old_id = legacy_sku_id(stored_id)
            external_id = old_id.split("#", 1)[0]
            if (finish, condition) != tuple(old_id.split("#")[1:]):
                raise ValueError(f"migrated SKU identity mismatch: {pk}")
            if (
                value(item, "game") != GAME
                or value(item, "external_source") != SOURCE
                or value(item, "external_id") != external_id
                or value(item, "gsi1sk") != f"SKU#{stored_id}"
                or not value(item, "gsi2sk").startswith(f"GAME#{GAME}#NAME#")
                or not value(item, "gsi2sk").endswith("#" + stored_id)
            ):
                raise ValueError(f"migrated SKU fields mismatch: {pk}")
            target_id = stored_id
        if value(item, "gsi2pk") != f"{user}#SKUS":
            raise ValueError(f"SKU browse partition mismatch: {pk}")
        if value(item, "gsi1pk") not in {f"{user}#DIRTY", f"{user}#CLEAN"}:
            raise ValueError(f"SKU dirty partition mismatch: {pk}")
        old_id = legacy_sku_id(target_id)
        reference_key = (user, old_id)
        if reference_key in sku_ids and sku_ids[reference_key] != target_id:
            raise ValueError(f"ambiguous SKU identity: {reference_key}")
        sku_ids[reference_key] = target_id
        sku_targets[(user, target_id)] = True
        if stored_id == target_id:
            sku_counts[user] += 1

    def resolve_sku(user, stored_id):
        if stored_id.startswith(f"{GAME}#{SOURCE}#"):
            old_id = legacy_sku_id(stored_id)
        else:
            if len(stored_id.split("#")) != 3:
                raise ValueError(f"unexpected legacy SKU ID: {stored_id}")
            old_id = stored_id
        target_id = sku_ids.get((user, old_id))
        if target_id is None or (user, target_id) not in sku_targets:
            raise ValueError(f"SKU reference has no record: {user} / {stored_id}")
        return target_id

    for item in items.values():
        pk, sk = key(item)
        if "#SKU#" not in pk or not sk.startswith("UNIT#"):
            continue
        user, stored_id = pk.split("#SKU#", 1)
        target_id = resolve_sku(user, stored_id)
        sequence = number(item, "sequence_number")
        if sk != f"UNIT#{sequence:010d}":
            raise ValueError(f"unit sort key mismatch: {pk} / {sk}")
        expected_gsi3pk = f"{user}#UNITS#{GAME}"
        target_key = (f"{user}#SKU#{target_id}", sk)
        prior = logical_units.get((user, sequence))
        if prior is not None and prior != target_key:
            raise ValueError(f"duplicate sequence number: {user} / {sequence}")
        logical_units[(user, sequence)] = target_key
        max_sequence[user] = max(max_sequence[user], sequence)
        if stored_id != target_id:
            if value(item, "gsi3pk") != f"{user}#UNITS" or "game" in item:
                raise ValueError(f"legacy unit index mismatch: {pk} / {sk}")
            after = copy.deepcopy(item)
            after["pk"] = {"S": target_key[0]}
            after["game"] = {"S": GAME}
            after["gsi3pk"] = {"S": expected_gsi3pk}
            add_move("unit", item, after, "gsi3pk")
        elif value(item, "game") != GAME or value(item, "gsi3pk") != expected_gsi3pk:
            raise ValueError(f"migrated unit fields mismatch: {pk} / {sk}")
        else:
            unit_counts[user] += 1

    for item in items.values():
        pk, sk = key(item)
        if "#SKU#" in pk:
            if sk != "SKU" and not sk.startswith("UNIT#"):
                raise ValueError(f"unexpected SKU partition item: {pk} / {sk}")
            continue
        if pk.endswith("#AUDIT"):
            user = pk.removesuffix("#AUDIT")
            if "sku_id" in item:
                target_id = resolve_sku(user, value(item, "sku_id"))
                after = copy.deepcopy(item)
                after["sku_id"] = {"S": target_id}
                add_update("audit", item, after, "sku_id")
            continue
        if "#IMPORT#" in pk and sk.startswith("ROW#"):
            user, import_id = pk.split("#IMPORT#", 1)
            if (user, f"IMPORT#{import_id}") not in items:
                raise ValueError(f"import row has no parent: {pk} / {sk}")
            after = copy.deepcopy(item)
            if "scryfall_id" in item:
                external_id = value(item, "scryfall_id")
                if not EXTERNAL_ID.fullmatch(external_id):
                    raise ValueError(f"invalid import row identity: {pk} / {sk}")
                if "external_id" in item or "external_source" in item:
                    raise ValueError(f"mixed import row identity: {pk} / {sk}")
                after["external_source"] = {"S": SOURCE}
                after["external_id"] = {"S": external_id}
                del after["scryfall_id"]
            elif value(item, "external_source") != SOURCE or not EXTERNAL_ID.fullmatch(
                value(item, "external_id")
            ):
                raise ValueError(f"invalid migrated import row identity: {pk} / {sk}")
            add_update("import_row", item, after, "scryfall_id")
            continue
        if "#SCAN#" in pk and sk.startswith("ROW#"):
            user, scan_id = pk.split("#SCAN#", 1)
            if (user, f"SCAN#{scan_id}") not in items:
                raise ValueError(f"scan row has no parent: {pk} / {sk}")
            after = copy.deepcopy(item)
            if "suggestions" in item:
                if set(item["suggestions"]) != {"L"}:
                    raise ValueError(f"invalid scan suggestions: {pk} / {sk}")
                for suggestion in after["suggestions"]["L"]:
                    fields = suggestion.get("M")
                    if fields is None:
                        raise ValueError(f"invalid scan suggestion: {pk} / {sk}")
                    if "scryfall_id" in fields:
                        external_id = fields["scryfall_id"].get("S")
                        if not external_id or not EXTERNAL_ID.fullmatch(external_id):
                            raise ValueError(
                                f"invalid scan suggestion identity: {pk} / {sk}"
                            )
                        if "external_id" in fields or "external_source" in fields:
                            raise ValueError(
                                f"mixed scan suggestion identity: {pk} / {sk}"
                            )
                        fields["external_source"] = {"S": SOURCE}
                        fields["external_id"] = {"S": external_id}
                        del fields["scryfall_id"]
                    elif fields.get("external_source") != {
                        "S": SOURCE
                    } or not EXTERNAL_ID.fullmatch(
                        fields.get("external_id", {}).get("S", "")
                    ):
                        raise ValueError(
                            f"invalid migrated scan suggestion identity: {pk} / {sk}"
                        )
            add_update("scan_row", item, after, "suggestions")
            continue
        if not pk.startswith("USER#") or "#" in pk[5:]:
            raise ValueError(f"unexpected item partition: {pk} / {sk}")
        if sk.startswith("IMPORT#"):
            if value(item, "status") in {"appraising", "confirming"}:
                raise ValueError(f"import is still active: {pk} / {sk}")
            after = copy.deepcopy(item)
            if "game" not in item:
                after["game"] = {"S": GAME}
            elif value(item, "game") != GAME:
                raise ValueError(f"unsupported import game: {pk} / {sk}")
            add_update("import", item, after, "game")
        elif sk.startswith("SCAN#"):
            if value(item, "status") == "identifying":
                raise ValueError(f"scan is still identifying: {pk} / {sk}")
            after = copy.deepcopy(item)
            if "game" not in item:
                after["game"] = {"S": GAME}
            elif value(item, "game") != GAME:
                raise ValueError(f"unsupported scan game: {pk} / {sk}")
            add_update("scan", item, after, "game")
        elif sk.startswith("JOB#"):
            if value(item, "status") not in {"succeeded", "failed"}:
                raise ValueError(f"job is still active: {pk} / {sk}")
        elif sk.startswith("ORDER#"):
            lines_attribute = item.get("lines")
            if lines_attribute is None:
                raise ValueError(f"order has no lines: {pk} / {sk}")
            if "S" in lines_attribute:
                if require_native_lines:
                    raise ValueError(f"run migration 002 before executing: {pk} / {sk}")
                lines = json.loads(lines_attribute["S"])
                if not isinstance(lines, list):
                    raise ValueError(f"order lines are not an array: {pk} / {sk}")
                for line in lines:
                    if not isinstance(line, dict):
                        raise ValueError(f"invalid order line: {pk} / {sk}")
                    target_id = resolve_sku(pk, line["sku_id"])
                    for sequence in line["allocated_sequence_numbers"]:
                        if (
                            type(sequence) is not int
                            or (pk, sequence) not in logical_units
                            or logical_units[(pk, sequence)][0]
                            != f"{pk}#SKU#{target_id}"
                        ):
                            raise ValueError(
                                f"order allocation has no unit: {pk} / {sk}"
                            )
                counts["json_orders"] += 1
            elif "L" in lines_attribute:
                after = copy.deepcopy(item)
                for line in after["lines"]["L"]:
                    fields = line.get("M")
                    if fields is None:
                        raise ValueError(f"invalid native order line: {pk} / {sk}")
                    target_id = resolve_sku(pk, fields["sku_id"]["S"])
                    allocated = fields["allocated_sequence_numbers"].get("L")
                    if allocated is None:
                        raise ValueError(f"invalid order allocation: {pk} / {sk}")
                    for sequence_value in allocated:
                        sequence = int(sequence_value["N"])
                        if (pk, sequence) not in logical_units or logical_units[
                            (pk, sequence)
                        ][0] != f"{pk}#SKU#{target_id}":
                            raise ValueError(
                                f"order allocation has no unit: {pk} / {sk}"
                            )
                    fields["sku_id"] = {"S": target_id}
                add_update("order", item, after, "lines")
            else:
                raise ValueError(f"unexpected order lines type: {pk} / {sk}")
        elif sk == "COUNTER#SEQUENCE" or sk == f"COUNTER#SEQUENCE#{GAME}":
            next_sequence = number(item, "next_sequence_number")
            if next_sequence < max_sequence[pk] + 1:
                raise ValueError(f"sequence counter is behind units: {pk} / {sk}")
            previous = counters.get(pk)
            if previous is not None and previous != next_sequence:
                raise ValueError(f"conflicting sequence counters: {pk}")
            counters[pk] = next_sequence
            if sk == "COUNTER#SEQUENCE":
                after = copy.deepcopy(item)
                after["sk"] = {"S": f"COUNTER#SEQUENCE#{GAME}"}
                after["game"] = {"S": GAME}
                add_move("counter", item, after, "next_sequence_number")
            elif value(item, "game") != GAME:
                raise ValueError(f"migrated counter game mismatch: {pk} / {sk}")
        elif sk == "REPORT":
            reports.append(Change("report", item, None, "report"))
        elif sk == "SETTINGS":
            continue
        else:
            raise ValueError(f"unexpected item: {pk} / {sk}")

    for user in max_sequence:
        if user not in counters:
            raise ValueError(f"units have no sequence counter: {user}")
    return Plan(
        copies, updates, deletes, reports, counts, dict(sku_counts), dict(unit_counts)
    )


def put_copy(client, table, change):
    client.put_item(
        TableName=table,
        Item=change.after,
        ConditionExpression="attribute_not_exists(#pk)",
        ExpressionAttributeNames={"#pk": "pk"},
    )


def put_update(client, table, change):
    old_attribute = change.before.get(change.condition_attribute)
    condition = (
        "attribute_not_exists(#field)" if old_attribute is None else "#field = :before"
    )
    request = {
        "TableName": table,
        "Item": change.after,
        "ConditionExpression": condition,
        "ExpressionAttributeNames": {"#field": change.condition_attribute},
    }
    if old_attribute is not None:
        request["ExpressionAttributeValues"] = {":before": old_attribute}
    client.put_item(**request)


def delete_source(client, table, change):
    client.delete_item(
        TableName=table,
        Key=item_key(change.before),
        ConditionExpression="#field = :before",
        ExpressionAttributeNames={"#field": change.condition_attribute},
        ExpressionAttributeValues={
            ":before": change.before[change.condition_attribute]
        },
    )


def execute(client, table, plan):
    for change in plan.copies:
        put_copy(client, table, change)
    copied = scan_items(client, table)
    for change in plan.deletes:
        if copied.get(key(change.after)) != change.after:
            raise ValueError(
                f"copied {change.kind} differs from source: {key(change.after)}"
            )

    for change in plan.updates:
        put_update(client, table, change)
    updated = scan_items(client, table)
    for change in plan.updates:
        if updated.get(key(change.after)) != change.after:
            raise ValueError(
                f"updated {change.kind} differs from source: {key(change.after)}"
            )
    for change in plan.deletes:
        if updated.get(key(change.after)) != change.after:
            raise ValueError(f"copied {change.kind} changed: {key(change.after)}")

    for change in plan.deletes:
        delete_source(client, table, change)
    for change in plan.reports:
        delete_source(client, table, change)

    final_items = scan_items(client, table)
    remaining = build_plan(final_items, require_native_lines=True)
    if remaining.copies or remaining.updates or remaining.deletes or remaining.reports:
        raise ValueError("migration left legacy records")
    return remaining


def index_count(client, table, index, partition, sort_prefix=None):
    request = {
        "TableName": table,
        "IndexName": index,
        "Select": "COUNT",
        "KeyConditionExpression": "#pk = :pk",
        "ExpressionAttributeNames": {"#pk": f"{index}pk"},
        "ExpressionAttributeValues": {":pk": {"S": partition}},
    }
    if sort_prefix is not None:
        request["KeyConditionExpression"] += " AND begins_with(#sk, :prefix)"
        request["ExpressionAttributeNames"]["#sk"] = f"{index}sk"
        request["ExpressionAttributeValues"][":prefix"] = {"S": sort_prefix}
    total = 0
    while True:
        response = client.query(**request)
        total += response["Count"]
        if "LastEvaluatedKey" not in response:
            return total
        request["ExclusiveStartKey"] = response["LastEvaluatedKey"]


def verify_indexes(client, table, final_plan, timeout_seconds=600):
    deadline = time.monotonic() + timeout_seconds
    while True:
        ready = True
        for user, count in final_plan.sku_counts.items():
            if (
                index_count(client, table, "gsi2", f"{user}#SKUS") != count
                or index_count(client, table, "gsi2", f"{user}#SKUS", f"GAME#{GAME}#")
                != count
            ):
                ready = False
            dirty_count = index_count(
                client, table, "gsi1", f"{user}#DIRTY", f"SKU#{GAME}#"
            )
            clean_count = index_count(
                client, table, "gsi1", f"{user}#CLEAN", f"SKU#{GAME}#"
            )
            all_dirty = index_count(client, table, "gsi1", f"{user}#DIRTY")
            all_clean = index_count(client, table, "gsi1", f"{user}#CLEAN")
            if dirty_count + clean_count != count or all_dirty + all_clean != count:
                ready = False
        for user, count in final_plan.unit_counts.items():
            if (
                index_count(client, table, "gsi3", f"{user}#UNITS#{GAME}") != count
                or index_count(client, table, "gsi3", f"{user}#UNITS") != 0
            ):
                ready = False
        if ready:
            return
        if time.monotonic() >= deadline:
            raise TimeoutError("game-scoped DynamoDB indexes did not converge")
        time.sleep(5)


def main():
    parser = argparse.ArgumentParser(
        description="Migrate legacy Magic inventory identity"
    )
    parser.add_argument(
        "--execute", action="store_true", help="write changes (default: dry run)"
    )
    parser.add_argument("--table-name", default=TABLE)
    parser.add_argument("--region", default=REGION)
    parser.add_argument("--endpoint-url", help="local DynamoDB endpoint for rehearsal")
    args = parser.parse_args()

    import boto3

    client = boto3.client(
        "dynamodb", region_name=args.region, endpoint_url=args.endpoint_url
    )
    items = scan_items(client, args.table_name)
    plan = build_plan(items, require_native_lines=args.execute)
    print(
        f"Read {len(items)} records; planned copies: {len(plan.copies)}, in-place updates: {len(plan.updates)}, legacy deletes: {len(plan.deletes)}, report deletes: {len(plan.reports)}"
    )
    print(
        f"Record types to copy: {dict(Counter(change.kind for change in plan.copies))}"
    )
    print(
        f"Record types to update: {dict(Counter(change.kind for change in plan.updates))}"
    )
    print(f"Orders still requiring migration 002: {plan.counts['json_orders']}")
    if not args.execute:
        print("DRY RUN: no writes made")
        return
    final_plan = execute(client, args.table_name, plan)
    verify_indexes(client, args.table_name, final_plan)
    print("Migration complete; table and game-scoped indexes verified")


if __name__ == "__main__":
    main()
