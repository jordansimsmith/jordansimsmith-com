import argparse
import json
import os
import random
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import Path


FETCH_FILTERS_URL = (
    "https://api.fetchtcg.com/v3/filters/cards?gameIds=mtg&sort=DATE_DESC"
)
FETCH_CARDS_URL = "https://api.fetchtcg.com/v3/cards"
SCRYFALL_SETS_URL = "https://api.scryfall.com/sets"
SCRYFALL_CARDS_URL = "https://api.scryfall.com/cards"
FETCH_HEADERS = {
    "Accept": "application/json, text/plain, */*",
    "Origin": "https://www.fetchtcg.com",
    "Referer": "https://www.fetchtcg.com/",
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/150.0.0.0 Safari/537.36"
    ),
}
SCRYFALL_HEADERS = {
    "Accept": "application/json",
    "User-Agent": "tcg-lister-set-mapper/1.0",
}
MAX_ATTEMPTS = 5
MIN_FETCH_INTERVAL_SECONDS = 1.0
MAX_FETCH_INTERVAL_SECONDS = 2.0
ADDITIONAL_FETCH_SET_IDS = {
    "plst": (3075,),
}


class JsonClient:
    def __init__(self, headers, *, rate_limited=False):
        self._headers = headers
        self._rate_limited = rate_limited
        self._has_requested = False

    def get(self, url):
        last_error = None
        for attempt in range(1, MAX_ATTEMPTS + 1):
            if self._rate_limited and self._has_requested:
                time.sleep(
                    random.uniform(
                        MIN_FETCH_INTERVAL_SECONDS,
                        MAX_FETCH_INTERVAL_SECONDS,
                    )
                )
            self._has_requested = True
            request = urllib.request.Request(url, headers=self._headers)
            try:
                with urllib.request.urlopen(request, timeout=30) as response:
                    payload = json.load(response)
                if not isinstance(payload, dict):
                    raise ValueError("expected a JSON object")
                return payload
            except (
                json.JSONDecodeError,
                OSError,
                urllib.error.HTTPError,
                urllib.error.URLError,
                ValueError,
            ) as error:
                last_error = error
                if attempt == MAX_ATTEMPTS:
                    break
                time.sleep(min(60.0, float(2**attempt)))
        raise RuntimeError(f"request failed for {url}") from last_error


def main():
    parser = argparse.ArgumentParser()
    workspace = Path(os.environ.get("BUILD_WORKSPACE_DIRECTORY", Path.cwd()))
    parser.add_argument(
        "--output",
        type=Path,
        default=workspace
        / "tcg_lister_api"
        / "scripts"
        / "list"
        / "fetch_set_mapping.py",
    )
    args = parser.parse_args()

    fetch_client = JsonClient(FETCH_HEADERS, rate_limited=True)
    scryfall_client = JsonClient(SCRYFALL_HEADERS)
    scryfall_sets = scryfall_client.get(SCRYFALL_SETS_URL).get("data")
    fetch_sets = fetch_client.get(FETCH_FILTERS_URL).get("sets")
    if not isinstance(scryfall_sets, list) or not isinstance(fetch_sets, list):
        raise RuntimeError("set catalogs were malformed")
    fetch_labels_by_id = {
        int(fetch_set["value"]): str(fetch_set["label"]) for fetch_set in fetch_sets
    }

    scryfall_by_name = defaultdict(list)
    scryfall_codes = set()
    for scryfall_set in scryfall_sets:
        code = str(scryfall_set["code"]).casefold()
        scryfall_codes.add(code)
        scryfall_by_name[_normalized_name(scryfall_set["name"])].append(scryfall_set)

    fetch_by_name = defaultdict(list)
    for fetch_set in fetch_sets:
        fetch_by_name[_normalized_name(fetch_set["label"])].append(fetch_set)

    mapping = defaultdict(dict)
    matched_fetch_ids = set()
    for normalized_name, matching_scryfall_sets in scryfall_by_name.items():
        matching_fetch_sets = fetch_by_name.get(normalized_name, [])
        if len(matching_scryfall_sets) != 1 or len(matching_fetch_sets) != 1:
            continue
        scryfall_set = matching_scryfall_sets[0]
        fetch_set = matching_fetch_sets[0]
        code = str(scryfall_set["code"]).casefold()
        fetch_id = int(fetch_set["value"])
        mapping[code][fetch_id] = str(fetch_set["label"])
        matched_fetch_ids.add(fetch_id)

    unresolved_fetch_sets = [
        fetch_set
        for fetch_set in fetch_sets
        if int(fetch_set["value"]) not in matched_fetch_ids
    ]
    print(
        f"matched {len(matched_fetch_ids)} of {len(fetch_sets)} Fetch sets by name; "
        f"probing {len(unresolved_fetch_sets)} remaining sets"
    )

    unresolved = []
    for index, fetch_set in enumerate(unresolved_fetch_sets, start=1):
        fetch_id = int(fetch_set["value"])
        label = str(fetch_set["label"])
        code = _discover_scryfall_code(
            fetch_client,
            scryfall_client,
            fetch_id,
            scryfall_codes,
        )
        if code is None:
            unresolved.append((fetch_id, label))
            status = "unresolved"
        else:
            mapping[code][fetch_id] = label
            status = code.upper()
        print(
            f"[{index}/{len(unresolved_fetch_sets)}] {fetch_id} {label}: {status}",
            flush=True,
        )

    _apply_additional_set_mappings(mapping, fetch_labels_by_id)
    args.output.write_text(_render_mapping(mapping))
    print(
        f"wrote {len(mapping)} Scryfall set codes to {args.output}; "
        f"{len(unresolved)} Fetch sets unresolved"
    )
    for fetch_id, label in unresolved:
        print(f"unresolved: {fetch_id} {label}")


def _discover_scryfall_code(
    fetch_client,
    scryfall_client,
    fetch_set_id,
    known_scryfall_codes,
):
    query = urllib.parse.urlencode(
        {
            "pageSize": 5,
            "pageOffset": 0,
            "sort": "DATE_DESC",
            "gameIds": "mtg",
            "sets": fetch_set_id,
        }
    )
    search = fetch_client.get(f"{FETCH_CARDS_URL}?{query}")
    search_results = search.get("searchResults")
    if not isinstance(search_results, dict):
        return None
    candidates = search_results.get("content")
    if not isinstance(candidates, list):
        return None

    for candidate in candidates:
        if not isinstance(candidate, dict) or not candidate.get("id"):
            continue
        card_id = urllib.parse.quote(str(candidate["id"]), safe="_-.")
        card = fetch_client.get(f"{FETCH_CARDS_URL}/{card_id}")
        references = card.get("externalReferences")
        if not isinstance(references, dict) or not references.get("scryfallId"):
            continue
        scryfall_id = urllib.parse.quote(str(references["scryfallId"]), safe="-")
        try:
            scryfall_card = scryfall_client.get(f"{SCRYFALL_CARDS_URL}/{scryfall_id}")
        except RuntimeError:
            continue
        code = str(scryfall_card.get("set", "")).casefold()
        if code in known_scryfall_codes:
            return code
    return None


def _normalized_name(value):
    value = re.sub(
        r"magic\s*:\s*the\s+gathering",
        "",
        str(value),
        flags=re.IGNORECASE,
    )
    return tuple(sorted(re.findall(r"[a-z0-9]+", value.casefold())))


def _apply_additional_set_mappings(mapping, fetch_labels_by_id):
    for code, fetch_set_ids in ADDITIONAL_FETCH_SET_IDS.items():
        for fetch_set_id in fetch_set_ids:
            label = fetch_labels_by_id.get(fetch_set_id)
            if label is None:
                raise RuntimeError(
                    f"additional Fetch set {fetch_set_id} was not in the catalog"
                )
            mapping.setdefault(code, {})[fetch_set_id] = label


def _render_mapping(mapping):
    lines = ["FETCH_SET_MAPPINGS = {"]
    for code in sorted(mapping):
        entries = ", ".join(
            f"({fetch_id}, {label!r})"
            for fetch_id, label in sorted(mapping[code].items())
        )
        if len(mapping[code]) == 1:
            entries += ","
        lines.append(f"    {code!r}: ({entries}),")
    lines.append("}")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    main()
