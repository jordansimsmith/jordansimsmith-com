#!/usr/bin/env python3
"""Generate the Japanese Pokémon FetchTCG set mapping from a Catalog snapshot."""

import argparse
import gzip
import json
import random
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

BASE_URL = "https://api.fetchtcg.com"
FILTERS_URL = f"{BASE_URL}/v3/filters/cards?gameIds=pkjp"
CARDS_URL = f"{BASE_URL}/v3/cards"
FETCHTCG_HEADERS = {
    "Accept": "application/json, text/plain, */*",
    "Origin": "https://www.fetchtcg.com",
    "Referer": "https://www.fetchtcg.com/",
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/120.0.0.0 Safari/537.36"
    ),
}

MAX_ATTEMPTS = 5
MIN_INTERVAL_SECONDS = 1.0
MAX_INTERVAL_SECONDS = 2.0
PAGE_SIZE = 50
SAMPLE_SIZE = 10
DEFAULT_OUTPUT = (
    Path(__file__).resolve().parent.parent
    / "src/main/resources/com/jordansimsmith/tcginventory/fetchtcg/server"
    / "fetchtcg_pokemon_jp_set_mapping.json"
)


def load_snapshot(path):
    with gzip.open(path, "rt", encoding="utf-8") as source:
        snapshot = json.load(source)
    if (
        snapshot["game"],
        snapshot["schema_version"],
        snapshot["catalog_source"],
        snapshot["category_id"],
    ) != ("pokemon_jp", 1, "tcgcsv", 85):
        raise ValueError(
            "expected a version 1 Japanese Pokémon TCGCSV Catalog snapshot"
        )

    groups_by_id = {group["group_id"]: group for group in snapshot["groups"]}
    if len(groups_by_id) != len(snapshot["groups"]):
        raise ValueError("snapshot has duplicate group IDs")

    products_by_id = {}
    for product in snapshot["products"]:
        product_id = str(product["product_id"])
        if product_id in products_by_id:
            raise ValueError("snapshot has duplicate product IDs")
        group = groups_by_id[product["group_id"]]
        set_code = group.get("set_code")
        products_by_id[product_id] = {
            "group_id": product["group_id"],
            "set_code": set_code.strip().lower()
            if set_code and set_code.strip()
            else None,
        }

    return {
        "snapshot_id": snapshot["snapshot_id"],
        "groups": groups_by_id,
        "products": products_by_id,
    }


def fetch_json(url):
    pace()
    return get_json(url, FETCHTCG_HEADERS)


def find_page(set_id, page_offset):
    query = urllib.parse.urlencode(
        {
            "gameIds": "pkjp",
            "sets": set_id,
            "pageSize": PAGE_SIZE,
            "pageOffset": page_offset,
            "sort": "DATE_DESC",
        }
    )
    page = fetch_json(f"{CARDS_URL}?{query}")["searchResults"]
    if page["number"] != page_offset or page["size"] != PAGE_SIZE:
        raise ValueError(f"unexpected FetchTCG page for set {set_id}: {page_offset}")
    return page


def probe_set(set_id, products_by_id):
    first_page = find_page(set_id, 0)
    total = first_page["totalElements"]
    count = min(SAMPLE_SIZE, total)
    pages = {0: first_page["content"]}
    seen_cards = set()
    matched_products = set()
    for index in range(count):
        position = index * (total - 1) // max(1, count - 1)
        page_offset, card_offset = divmod(position, PAGE_SIZE)
        if page_offset not in pages:
            pages[page_offset] = find_page(set_id, page_offset)["content"]
        card_id = pages[page_offset][card_offset]["id"]
        if card_id in seen_cards:
            continue
        seen_cards.add(card_id)
        escaped_card_id = urllib.parse.quote(card_id, safe="")
        detail = fetch_json(f"{CARDS_URL}/{escaped_card_id}")
        if detail["id"] != card_id:
            raise ValueError(f"unexpected FetchTCG card details for {card_id}")
        references = detail.get("externalReferences") or {}
        product_id = str(references.get("tcgId"))
        if product_id in products_by_id and products_by_id[product_id]["set_code"]:
            matched_products.add(product_id)
    return matched_products


def generate(snapshot):
    products_by_id = snapshot["products"]
    sets = {
        int(entry["value"]): entry["label"] for entry in fetch_json(FILTERS_URL)["sets"]
    }

    mapping = defaultdict(dict)
    matched_products = set()
    matched_groups = set()
    unresolved = []
    set_ids = sorted(sets)
    print(f"snapshot ID: {snapshot['snapshot_id']}")
    print(f"sampling {len(set_ids)} FetchTCG sets", flush=True)
    for index, set_id in enumerate(set_ids, start=1):
        products = probe_set(set_id, products_by_id)
        name = sets[set_id]
        codes = {products_by_id[product_id]["set_code"] for product_id in products}
        for code in codes:
            mapping[code][set_id] = name
        matched_products.update(products)
        matched_groups.update(
            products_by_id[product_id]["group_id"] for product_id in products
        )
        if not products:
            unresolved.append((set_id, name))
        if index % 25 == 0:
            print(f"sampled {index}/{len(set_ids)} sets", flush=True)

    for set_id, name in sorted(unresolved):
        print(f"unresolved FetchTCG set: {set_id} {name}")

    eligible_groups = {
        group_id: group
        for group_id, group in snapshot["groups"].items()
        if group.get("set_code") and group["set_code"].strip()
    }
    groups_without_evidence = sorted(eligible_groups.keys() - matched_groups)
    print(
        "eligible catalog groups with direct product evidence: "
        f"{len(matched_groups)}/{len(eligible_groups)}"
    )
    for group_id in groups_without_evidence:
        group = eligible_groups[group_id]
        print(
            "eligible catalog group without direct product evidence: "
            f"{group_id} {group['set_code']} {group['set_name']}"
        )

    product_counts = Counter(
        product["set_code"]
        for product in products_by_id.values()
        if product["set_code"]
    )
    for code in sorted(product_counts.keys() - mapping.keys()):
        print(f"unmapped Catalog code: {code} ({product_counts[code]} products)")
    eligible_product_count = sum(product_counts.values())
    mapped_product_count = sum(product_counts[code] for code in mapping)
    print(
        "eligible products with a mapped set code: "
        f"{mapped_product_count}/{eligible_product_count}"
    )
    print(f"exact product IDs observed: {len(matched_products)}")
    print(f"FetchTCG sets sampled: {len(set_ids)}")
    print(f"FetchTCG sets unresolved: {len(unresolved)}")
    return {
        code: [
            {"set_id": set_id, "set_name": set_name}
            for set_id, set_name in sorted(set_entries.items())
        ]
        for code, set_entries in sorted(mapping.items())
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    mapping = generate(load_snapshot(args.snapshot))
    args.output.write_text(json.dumps(mapping, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {len(mapping)} set codes to {args.output}")


def get_json(url, headers):
    """Fetch a URL and return the parsed JSON. Retries on transient failures."""
    for attempt in range(1, MAX_ATTEMPTS + 1):
        request = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except (json.JSONDecodeError, OSError, urllib.error.URLError) as error:
            if attempt == MAX_ATTEMPTS:
                raise RuntimeError(f"request failed for {url}") from error
            time.sleep(min(60.0, float(2**attempt)))
    raise RuntimeError(f"request failed for {url}")


def pace():
    """Sleep 1-2 seconds (rate limiting for FetchTCG)."""
    time.sleep(random.uniform(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS))


if __name__ == "__main__":
    main()
