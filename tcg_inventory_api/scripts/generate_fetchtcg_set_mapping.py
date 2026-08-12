#!/usr/bin/env python3
"""Generate the Scryfall-to-FetchTCG set mapping JSON.

Probes every FetchTCG set by sampling a card, looking up its Scryfall ID,
and resolving the Scryfall set code. Produces fetchtcg_set_mapping.json.

Usage:
    python generate_fetchtcg_set_mapping.py [--output PATH]
"""

import argparse
import json
import random
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import Path

FETCHTCG_FILTERS_URL = (
    "https://api.fetchtcg.com/v3/filters/cards?gameIds=mtg&sort=DATE_DESC"
)
FETCHTCG_CARDS_URL = "https://api.fetchtcg.com/v3/cards"
SCRYFALL_SETS_URL = "https://api.scryfall.com/sets"
SCRYFALL_CARDS_URL = "https://api.scryfall.com/cards"

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
SCRYFALL_HEADERS = {
    "Accept": "application/json",
    "User-Agent": "tcg-inventory-set-mapper/1.0",
}

MAX_ATTEMPTS = 5
MIN_INTERVAL_SECONDS = 1.0
MAX_INTERVAL_SECONDS = 2.0

# manual overrides: FetchTCG sets that should also map to a Scryfall code
# even though the probe would resolve them to a different code
ADDITIONAL_MAPPINGS = {
    "plst": [3075],  # Mystery Booster contains plst-coded cards
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    default_output = (
        Path(__file__).resolve().parent.parent
        / "src"
        / "main"
        / "resources"
        / "fetchtcg_set_mapping.json"
    )
    parser.add_argument("--output", type=Path, default=default_output)
    args = parser.parse_args()

    print("fetching Scryfall set catalog...")
    scryfall_sets = _get_json(SCRYFALL_SETS_URL, SCRYFALL_HEADERS).get("data", [])
    scryfall_codes = {s["code"].casefold() for s in scryfall_sets}
    print(f"  {len(scryfall_codes)} Scryfall set codes")

    print("fetching FetchTCG set list...")
    fetchtcg_sets = _get_json(FETCHTCG_FILTERS_URL, FETCHTCG_HEADERS).get("sets", [])
    print(f"  {len(fetchtcg_sets)} FetchTCG sets")

    mapping = defaultdict(dict)
    unresolved = []
    has_fetchtcg_request = False

    for index, fetchtcg_set in enumerate(fetchtcg_sets, start=1):
        set_id = int(fetchtcg_set["value"])
        label = str(fetchtcg_set["label"])

        code = _probe_set(set_id, scryfall_codes, has_fetchtcg_request)
        has_fetchtcg_request = True

        if code:
            mapping[code][set_id] = label
            status = code.upper()
        else:
            unresolved.append((set_id, label))
            status = "unresolved"

        print(f"[{index}/{len(fetchtcg_sets)}] {set_id} {label}: {status}")

    for code, set_ids in ADDITIONAL_MAPPINGS.items():
        for set_id in set_ids:
            label = next(
                (s["label"] for s in fetchtcg_sets if int(s["value"]) == set_id), None
            )
            if label:
                mapping[code][set_id] = label

    output = {}
    for code in sorted(mapping):
        output[code] = [
            {"set_id": sid, "set_name": name}
            for sid, name in sorted(mapping[code].items())
        ]

    args.output.write_text(json.dumps(output, indent=2) + "\n")
    print(
        f"\nwrote {len(output)} Scryfall set codes to {args.output}"
        f" ({len(unresolved)} FetchTCG sets unresolved)"
    )
    for set_id, label in unresolved:
        print(f"  unresolved: {set_id} {label}")


def _probe_set(fetchtcg_set_id, scryfall_codes, pace_first):
    """Sample a card from the FetchTCG set and resolve its Scryfall set code."""
    query = urllib.parse.urlencode(
        {
            "pageSize": 5,
            "pageOffset": 0,
            "sort": "DATE_DESC",
            "gameIds": "mtg",
            "sets": fetchtcg_set_id,
        }
    )

    if pace_first:
        _pace()
    search = _get_json(f"{FETCHTCG_CARDS_URL}?{query}", FETCHTCG_HEADERS)
    results = search.get("searchResults", {})
    candidates = results.get("content", [])

    for candidate in candidates:
        if not isinstance(candidate, dict) or not candidate.get("id"):
            continue

        card_id = urllib.parse.quote(str(candidate["id"]), safe="_-.")
        _pace()
        card = _get_json(f"{FETCHTCG_CARDS_URL}/{card_id}", FETCHTCG_HEADERS)
        references = card.get("externalReferences", {})
        scryfall_id = references.get("scryfallId")
        if not scryfall_id:
            continue

        scryfall_id = urllib.parse.quote(str(scryfall_id), safe="-")
        try:
            scryfall_card = _get_json(
                f"{SCRYFALL_CARDS_URL}/{scryfall_id}", SCRYFALL_HEADERS
            )
        except RuntimeError:
            continue

        code = str(scryfall_card.get("set", "")).casefold()
        if code in scryfall_codes:
            return code

    return None


def _get_json(url, headers):
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


def _pace():
    """Sleep 1-2 seconds (rate limiting for FetchTCG)."""
    time.sleep(random.uniform(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS))


if __name__ == "__main__":
    main()
