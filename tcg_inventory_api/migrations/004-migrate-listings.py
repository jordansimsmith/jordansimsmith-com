#!/usr/bin/env python3

import argparse

import boto3

REGION = "ap-southeast-2"
TABLE = "tcg_inventory"


def parse_args():
    parser = argparse.ArgumentParser(
        description="Copy legacy SKU listing associations. Pause writes and workers "
        "until migration and application cutover finish; never rerun after resuming traffic."
    )
    parser.add_argument("--table-name", default=TABLE)
    parser.add_argument("--region", default=REGION)
    parser.add_argument("--endpoint-url")
    parser.add_argument(
        "--execute", action="store_true", help="Write changes (default: dry run)"
    )
    return parser.parse_args()


def read_item(client, table, item):
    return client.get_item(
        TableName=table,
        Key={name: item[name] for name in ("pk", "sk")},
        ConsistentRead=True,
    ).get("Item")


def build_plan(client, table):
    destinations = {}
    pairs = []
    request = {"TableName": table, "ConsistentRead": True}
    page = 0
    while True:
        response = client.scan(**request)
        page += 1
        print(f"Scan page {page}: {len(response.get('Items', []))} items", flush=True)
        for source in response.get("Items", []):
            if source.get("sk") != {"S": "SKU"}:
                continue
            listing = source.get("fetchtcg_listing_id")
            if listing is None or listing == {"NULL": True}:
                continue
            pk = source["pk"]["S"]
            owner, sku_id = pk.split("#SKU#", 1)
            if not owner.startswith("USER#") or not owner[5:] or not sku_id:
                raise ValueError(f"Invalid SKU partition: {pk}")
            if source["sku_id"] != {"S": sku_id}:
                raise ValueError(f"SKU identity mismatch: {pk}")
            if set(listing) != {"N"}:
                raise ValueError(f"Invalid listing ID: {pk}")
            listing_id = int(listing["N"])
            if not -(2**31) <= listing_id < 2**31:
                raise ValueError(f"Listing ID outside Java integer range: {pk}")
            fields = {
                "pk": {"S": f"{owner}#LISTINGS"},
                "user": {"S": owner[5:]},
                "sku_id": {"S": sku_id},
                "listing_id": {"N": str(listing_id)},
            }
            pair = [
                dict(fields, sk={"S": f"SKU#{sku_id}"}),
                dict(fields, sk={"S": f"LISTING#{listing_id}"}),
            ]
            print(f"Validating destination records for SKU {len(pairs) + 1}", flush=True)
            for item in pair:
                key = (item["pk"]["S"], item["sk"]["S"])
                if key in destinations and destinations[key] != item:
                    raise ValueError(f"Conflicting source association: {key}")
                existing = read_item(client, table, item)
                if existing is not None and existing != item:
                    raise ValueError(f"Conflicting destination: {key}")
                destinations[key] = item
            pairs.append(pair)
        continuation = response.get("LastEvaluatedKey")
        if not continuation:
            return pairs
        request["ExclusiveStartKey"] = continuation


def main():
    args = parse_args()
    client = boto3.client(
        "dynamodb", region_name=args.region, endpoint_url=args.endpoint_url
    )
    print(
        f"{'EXECUTE' if args.execute else 'DRY RUN'}: scanning "
        f"{args.table_name} in {args.region}",
        flush=True,
    )
    pairs = build_plan(client, args.table_name)
    copied = 0
    matching = 0
    for index, pair in enumerate(pairs, start=1):
        print(f"Checking SKU {index}/{len(pairs)}", flush=True)
        if all(read_item(client, args.table_name, item) == item for item in pair):
            matching += 1
            continue
        if not args.execute:
            continue
        client.transact_write_items(
            TransactItems=[
                {
                    "Put": {
                        "TableName": args.table_name,
                        "Item": item,
                        "ConditionExpression": "attribute_not_exists(pk) OR "
                        "(#user = :user AND sku_id = :sku AND listing_id = :listing)",
                        "ExpressionAttributeNames": {"#user": "user"},
                        "ExpressionAttributeValues": {
                            ":user": item["user"],
                            ":sku": item["sku_id"],
                            ":listing": item["listing_id"],
                        },
                    }
                }
                for item in pair
            ]
        )
        for item in pair:
            if read_item(client, args.table_name, item) != item:
                raise RuntimeError("Listings migration readback mismatch")
        copied += 1
        print(f"Copied and verified SKU {index}/{len(pairs)}", flush=True)
    print(
        f"Source SKUs: {len(pairs)}; already matching: {matching}; "
        f"{'copied' if args.execute else 'would copy'}: "
        f"{copied if args.execute else len(pairs) - matching}",
        flush=True,
    )


if __name__ == "__main__":
    main()
