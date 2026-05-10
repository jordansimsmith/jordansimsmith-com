#!/usr/bin/env python3

"""
Destructively rebuild the japanese_dictionary DynamoDB table from upstream
Yomitan zips (Jitendex, JPDB, Kanjium).

Pipeline:

  1. Load Jitendex term_bank entries; keep argmax(score) per JMdict sequence.
  2. Load JPDB term_meta_bank frequencies; key by (term, reading) -> min(value).
  3. Load Kanjium term_meta_bank pitches; key by (term, reading) -> first valid
     position (validated against mora count of the reading).
  4. Compute Modified Hepburn romaji from each reading.
  5. Clear all TERM#... items via BatchWriteItem deletes (chunks of 25).
  6. Upload all rebuilt items via BatchWriteItem puts (chunks of 25).

Dry-run by default; pass --execute to actually write to DynamoDB. Requires
AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY in the environment when executing.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import zipfile
from pathlib import Path

import boto3
from botocore.config import Config

DYNAMODB_TABLE_NAME = "japanese_dictionary"
DYNAMODB_REGION = "ap-southeast-2"
TERM_PREFIX = "TERM#"

BATCH_SIZE = 25
THROTTLE_SLEEP_SECONDS = 1.0
MAX_BATCH_RETRIES = 8

TERM_BANK_RE = re.compile(r"^term_bank_\d+\.json$")
TERM_META_BANK_RE = re.compile(r"^term_meta_bank_\d+\.json$")


# --------------------------------------------------------------------------
# Modified Hepburn (vowel-doubled) computed at ingest.
# --------------------------------------------------------------------------

_BASE = {
    "あ": "a",
    "い": "i",
    "う": "u",
    "え": "e",
    "お": "o",
    "か": "ka",
    "き": "ki",
    "く": "ku",
    "け": "ke",
    "こ": "ko",
    "が": "ga",
    "ぎ": "gi",
    "ぐ": "gu",
    "げ": "ge",
    "ご": "go",
    "さ": "sa",
    "し": "shi",
    "す": "su",
    "せ": "se",
    "そ": "so",
    "ざ": "za",
    "じ": "ji",
    "ず": "zu",
    "ぜ": "ze",
    "ぞ": "zo",
    "た": "ta",
    "ち": "chi",
    "つ": "tsu",
    "て": "te",
    "と": "to",
    "だ": "da",
    "ぢ": "ji",
    "づ": "zu",
    "で": "de",
    "ど": "do",
    "な": "na",
    "に": "ni",
    "ぬ": "nu",
    "ね": "ne",
    "の": "no",
    "は": "ha",
    "ひ": "hi",
    "ふ": "fu",
    "へ": "he",
    "ほ": "ho",
    "ば": "ba",
    "び": "bi",
    "ぶ": "bu",
    "べ": "be",
    "ぼ": "bo",
    "ぱ": "pa",
    "ぴ": "pi",
    "ぷ": "pu",
    "ぺ": "pe",
    "ぽ": "po",
    "ま": "ma",
    "み": "mi",
    "む": "mu",
    "め": "me",
    "も": "mo",
    "や": "ya",
    "ゆ": "yu",
    "よ": "yo",
    "ら": "ra",
    "り": "ri",
    "る": "ru",
    "れ": "re",
    "ろ": "ro",
    "わ": "wa",
    "ゐ": "i",
    "ゑ": "e",
    "を": "o",
    "ん": "n",
}

_YOON = {
    "き": "k",
    "ぎ": "g",
    "し": "sh",
    "じ": "j",
    "ち": "ch",
    "に": "n",
    "ひ": "h",
    "び": "b",
    "ぴ": "p",
    "み": "m",
    "り": "r",
}

_YOON_VOWEL = {"ゃ": "a", "ゅ": "u", "ょ": "o"}

_SMALL_YAYUYO = set("ゃゅょゎャュョヮ")


def _kata_to_hira(s):
    out = []
    for ch in s:
        cp = ord(ch)
        out.append(chr(cp - 0x60) if 0x30A1 <= cp <= 0x30F6 else ch)
    return "".join(out)


def kana_to_romaji(reading):
    s = _kata_to_hira(reading)
    out = []
    i = 0
    while i < len(s):
        ch = s[i]
        nxt = s[i + 1] if i + 1 < len(s) else ""
        if ch == "っ":
            i += 1
            continue
        if ch == "ー":
            if out and out[-1] and out[-1][-1] in "aeiou":
                out.append(out[-1][-1])
            i += 1
            continue
        if nxt in _YOON_VOWEL and ch in _YOON:
            stem = _YOON[ch]
            vowel = _YOON_VOWEL[nxt]
            piece = stem + vowel if stem in ("sh", "ch", "j") else stem + "y" + vowel
            if i > 0 and s[i - 1] == "っ":
                piece = ("t" + piece) if piece.startswith("ch") else (piece[0] + piece)
            out.append(piece)
            i += 2
            continue
        if ch in _BASE:
            piece = _BASE[ch]
            if i > 0 and s[i - 1] == "っ" and piece and piece[0] not in "aeiou":
                piece = ("t" + piece) if piece.startswith("ch") else (piece[0] + piece)
            out.append(piece)
            i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def mora_count(reading):
    return sum(1 for ch in reading if ch not in _SMALL_YAYUYO)


# --------------------------------------------------------------------------
# Source loaders
# --------------------------------------------------------------------------


def load_jitendex(zip_path):
    """Drop redirects, keep argmax(score) per JMdict sequence."""
    best = {}
    with zipfile.ZipFile(zip_path) as zf:
        for name in zf.namelist():
            if not TERM_BANK_RE.match(name):
                continue
            with zf.open(name) as f:
                for raw in json.load(f):
                    sequence = raw[6] if len(raw) > 6 else None
                    if not isinstance(sequence, int) or sequence < 0:
                        continue
                    score = raw[4] if len(raw) > 4 else 0
                    cur = best.get(sequence)
                    if cur is None or score > cur[0]:
                        best[sequence] = (score, raw)

    out = {}
    for sequence, (_, raw) in best.items():
        expression = raw[0]
        reading = raw[1] or expression
        out[sequence] = {
            "expression": expression,
            "reading": reading,
            "glossary_raw": raw[5],
        }
    return out


def load_jpdb_frequencies(zip_path):
    """Build (term, reading) -> min(value); ignore the kana-only ㋕ flag."""
    out = {}
    with zipfile.ZipFile(zip_path) as zf:
        for name in zf.namelist():
            if not TERM_META_BANK_RE.match(name):
                continue
            with zf.open(name) as f:
                for entry in json.load(f):
                    term = entry[0]
                    mode = entry[1]
                    payload = entry[2]
                    if mode != "freq" or not isinstance(payload, dict):
                        continue
                    if "reading" in payload and "frequency" in payload:
                        key = (term, payload["reading"])
                        value = payload["frequency"]["value"]
                    elif "value" in payload:
                        key = (term, term)
                        value = payload["value"]
                    else:
                        continue
                    cur = out.get(key)
                    if cur is None or value < cur:
                        out[key] = value
    return out


def load_kanjium_pitches(zip_path):
    """Build (term, reading) -> [position, ...] in source order."""
    out = {}
    with zipfile.ZipFile(zip_path) as zf:
        for name in zf.namelist():
            if not TERM_META_BANK_RE.match(name):
                continue
            with zf.open(name) as f:
                for entry in json.load(f):
                    term = entry[0]
                    mode = entry[1]
                    payload = entry[2]
                    if mode != "pitch" or not isinstance(payload, dict):
                        continue
                    reading = payload.get("reading", "")
                    positions = [
                        p.get("position")
                        for p in payload.get("pitches", [])
                        if isinstance(p, dict) and isinstance(p.get("position"), int)
                    ]
                    if positions:
                        out.setdefault((term, reading), []).extend(positions)
    return out


def select_pitch(positions, reading):
    n = mora_count(reading)
    for pos in positions:
        if 0 <= pos <= n:
            return pos
    return None


# --------------------------------------------------------------------------
# Build pipeline
# --------------------------------------------------------------------------


def build_records(jitendex_zip, jpdb_zip, kanjium_zip):
    print("loading jitendex (definitions)...", flush=True)
    terms = load_jitendex(jitendex_zip)
    print(f"  {len(terms):,} canonical headwords")

    print("loading JPDB (frequency)...", flush=True)
    freq = load_jpdb_frequencies(jpdb_zip)
    print(f"  {len(freq):,} (term, reading) frequency keys")

    print("loading kanjium (pitch)...", flush=True)
    pitches = load_kanjium_pitches(kanjium_zip)
    print(f"  {len(pitches):,} (term, reading) pitch keys")

    records = []
    n_freq = 0
    n_pitch = 0
    for sequence, term in terms.items():
        key = (term["expression"], term["reading"])
        record = {
            "sequence": sequence,
            "expression": term["expression"],
            "reading": term["reading"],
            "reading_romaji": kana_to_romaji(term["reading"]),
            "frequency_rank": freq.get(key),
            "pitch": (
                select_pitch(pitches[key], term["reading"]) if key in pitches else None
            ),
            "glossary_raw": term["glossary_raw"],
        }
        if record["frequency_rank"] is not None:
            n_freq += 1
        if record["pitch"] is not None:
            n_pitch += 1
        records.append(record)

    return records, n_freq, n_pitch


# --------------------------------------------------------------------------
# DynamoDB IO
# --------------------------------------------------------------------------


def chunked(iterable, size):
    chunk = []
    for item in iterable:
        chunk.append(item)
        if len(chunk) >= size:
            yield chunk
            chunk = []
    if chunk:
        yield chunk


def scan_existing_term_keys(dynamodb_client):
    keys = []
    paginator = dynamodb_client.get_paginator("scan")
    for page in paginator.paginate(
        TableName=DYNAMODB_TABLE_NAME,
        FilterExpression="begins_with(pk, :p)",
        ExpressionAttributeValues={":p": {"S": TERM_PREFIX}},
        ProjectionExpression="pk, sk",
    ):
        for item in page["Items"]:
            keys.append({"pk": item["pk"], "sk": item["sk"]})
    return keys


def batch_write_with_retry(dynamodb_client, requests):
    request_items = {DYNAMODB_TABLE_NAME: list(requests)}
    sleep_seconds = THROTTLE_SLEEP_SECONDS
    for attempt in range(MAX_BATCH_RETRIES):
        response = dynamodb_client.batch_write_item(RequestItems=request_items)
        unprocessed = response.get("UnprocessedItems", {})
        if not unprocessed:
            return
        request_items = unprocessed
        time.sleep(sleep_seconds)
        sleep_seconds *= 2
    raise RuntimeError(
        f"BatchWriteItem still has unprocessed items after {MAX_BATCH_RETRIES} retries"
    )


def clear_existing_terms(dynamodb_client):
    print("scanning existing TERM# items...", flush=True)
    keys = scan_existing_term_keys(dynamodb_client)
    print(f"  found {len(keys):,} existing items")
    if not keys:
        return 0
    cleared = 0
    for chunk in chunked(keys, BATCH_SIZE):
        requests = [{"DeleteRequest": {"Key": key}} for key in chunk]
        batch_write_with_retry(dynamodb_client, requests)
        cleared += len(chunk)
        if cleared % (BATCH_SIZE * 40) == 0:
            print(f"  deleted {cleared:,} / {len(keys):,}", flush=True)
    return cleared


def record_to_item(record):
    sequence = record["sequence"]
    pk = f"{TERM_PREFIX}{sequence}"
    item = {
        "pk": {"S": pk},
        "sk": {"S": pk},
        "sequence": {"N": str(sequence)},
        "expression": {"S": record["expression"]},
        "reading": {"S": record["reading"]},
        "reading_romaji": {"S": record["reading_romaji"]},
        "glossary_raw": {"S": json.dumps(record["glossary_raw"], ensure_ascii=False)},
    }
    if record["frequency_rank"] is not None:
        item["frequency_rank"] = {"N": str(record["frequency_rank"])}
    if record["pitch"] is not None:
        item["pitch"] = {"N": str(record["pitch"])}
    # sparse GSIs: only index the item where the sort value is non-empty.
    # DynamoDB rejects empty strings for index key attributes; an item
    # with no romaji simply will not appear in the romaji index.
    if record["expression"]:
        item["gsi1pk"] = {"S": "EXPRESSION"}
        item["gsi1sk"] = {"S": record["expression"]}
    if record["reading"]:
        item["gsi2pk"] = {"S": "READING"}
        item["gsi2sk"] = {"S": record["reading"]}
    if record["reading_romaji"]:
        item["gsi3pk"] = {"S": "ROMAJI"}
        item["gsi3sk"] = {"S": record["reading_romaji"]}
    return item


def upload_records(dynamodb_client, records):
    items = [record_to_item(r) for r in records]
    written = 0
    for chunk in chunked(items, BATCH_SIZE):
        requests = [{"PutRequest": {"Item": item}} for item in chunk]
        batch_write_with_retry(dynamodb_client, requests)
        written += len(chunk)
        if written % (BATCH_SIZE * 40) == 0:
            print(f"  uploaded {written:,} / {len(items):,}", flush=True)
    return written


# --------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------


def default_zip(name):
    return Path.home() / "Downloads" / name


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--jitendex",
        type=Path,
        default=default_zip("jitendex-yomitan.zip"),
        help="path to the Jitendex Yomitan zip",
    )
    parser.add_argument(
        "--jpdb",
        type=Path,
        default=default_zip("JPDB_v2.2_Frequency_Kana_2024-10-13.zip"),
        help="path to the JPDB Frequency Kana Yomitan zip",
    )
    parser.add_argument(
        "--kanjium",
        type=Path,
        default=default_zip("kanjium_pitch_accents.zip"),
        help="path to the Kanjium Pitch Accents Yomitan zip",
    )
    parser.add_argument(
        "--execute",
        action="store_true",
        help="actually clear and upload (default is dry-run)",
    )
    args = parser.parse_args(argv)

    for path in (args.jitendex, args.jpdb, args.kanjium):
        if not path.exists():
            print(f"error: {path} not found", file=sys.stderr)
            return 1

    dry_run = not args.execute
    if dry_run:
        print("DRY RUN mode - use --execute to write to DynamoDB\n")

    started = time.time()

    records, n_freq, n_pitch = build_records(args.jitendex, args.jpdb, args.kanjium)
    total = len(records)
    print()
    print(f"built {total:,} term records")
    print(f"  with frequency_rank: {n_freq:,} ({n_freq * 100 / total:.1f}%)")
    print(f"  with pitch         : {n_pitch:,} ({n_pitch * 100 / total:.1f}%)")

    if dry_run:
        print()
        print(f"DRY RUN: would clear and upload to {DYNAMODB_TABLE_NAME}")
        print(f"elapsed: {time.time() - started:.1f}s")
        return 0

    aws_access_key_id = os.environ["AWS_ACCESS_KEY_ID"]
    aws_secret_access_key = os.environ["AWS_SECRET_ACCESS_KEY"]
    dynamodb_client = boto3.client(
        "dynamodb",
        region_name=DYNAMODB_REGION,
        aws_access_key_id=aws_access_key_id,
        aws_secret_access_key=aws_secret_access_key,
        config=Config(retries={"max_attempts": 10, "mode": "adaptive"}),
    )

    cleared = clear_existing_terms(dynamodb_client)
    print(f"cleared {cleared:,} existing items\n")

    print(f"uploading {total:,} term records...", flush=True)
    written = upload_records(dynamodb_client, records)
    print(f"uploaded {written:,} items\n")

    elapsed = time.time() - started
    print(
        f"\nrebuilt {DYNAMODB_TABLE_NAME}: "
        f"cleared {cleared:,}, uploaded {written:,}, elapsed {elapsed:.1f}s, "
        f"frequency_rank coverage {n_freq * 100 / total:.1f}%, "
        f"pitch coverage {n_pitch * 100 / total:.1f}%"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
