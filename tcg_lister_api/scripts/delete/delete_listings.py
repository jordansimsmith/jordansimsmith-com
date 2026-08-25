import argparse
import csv
import json
import os
import signal
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal
from enum import Enum
from pathlib import Path
from typing import Callable, Sequence

from fetchtcg_client import FetchTcgClient


REPORT_SCHEMA_VERSION = 1
ANSI_RESET = "\033[0m"
STATUS_COLORS = {
    "PLANNED": "\033[33m",
    "SUCCEEDED": "\033[32m",
    "FAILED": "\033[31m",
}
CSV_FIELDS = [
    "inventory_offset",
    "stable_position",
    "listing_id",
    "fetch_card_id",
    "scryfall_id",
    "name",
    "collector_number",
    "set_id",
    "set_name",
    "finish",
    "condition",
    "remaining_quantity",
    "listed_price_nzd",
    "mutation_status",
    "mutation_error",
]


class MutationStatus(str, Enum):
    PLANNED = "PLANNED"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"


class ControlledTermination(RuntimeError):
    pass


@dataclass(frozen=True)
class DeleteRecord:
    inventory_offset: int
    stable_position: int
    listing_id: int
    fetch_card_id: str
    scryfall_id: str
    name: str
    collector_number: str
    set_id: int
    set_name: str
    finish: str
    condition: str
    remaining_quantity: int
    listed_price_nzd: Decimal
    mutation_status: MutationStatus
    mutation_error: str | None


@dataclass
class DeleteRun:
    generated_at: datetime
    execution_mode: str
    requested_offset: int
    requested_limit: int | None
    records: list[DeleteRecord]
    managed_listing_count: int | None = None
    selected_listing_count: int = 0
    completed_listing_count: int = 0
    next_offset: int = 0
    request_count: int = 0
    complete: bool = False
    portfolio_complete: bool = False
    error: str | None = None


def parse_args(argv: Sequence[str] | None = None):
    parser = argparse.ArgumentParser(description="Delete active Fetch TCG listings.")
    parser.add_argument(
        "--offset",
        type=_nonnegative_int,
        default=0,
        help="skip N listings after numeric listing-id sorting",
    )
    parser.add_argument(
        "--limit",
        type=_positive_int,
        help="process at most N listings",
    )
    parser.add_argument(
        "--execute",
        action="store_true",
        help="delete listings; default is dry-run",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="print Fetch request diagnostics",
    )
    return parser.parse_args(argv)


def run_deletes(
    client,
    *,
    output_dir,
    offset=0,
    limit=None,
    execute=False,
    generated_at=None,
    use_color=False,
    output: Callable[[str], None] = print,
):
    generated_at = generated_at or datetime.now(timezone.utc)
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    run = DeleteRun(
        generated_at=generated_at,
        execution_mode="execute" if execute else "dry_run",
        requested_offset=offset,
        requested_limit=limit,
        records=[],
        next_offset=offset,
    )

    try:
        _checkpoint(run, client, output_dir)
        try:
            managed_listings = sorted(
                client.get_managed_listings(),
                key=lambda listing: listing.listing_id,
            )
        except (KeyboardInterrupt, Exception) as error:
            run.error = _safe_error(error)
            return run

        run.managed_listing_count = len(managed_listings)
        selected = managed_listings[offset:]
        if limit is not None:
            selected = selected[:limit]
        run.selected_listing_count = len(selected)
        _checkpoint(run, client, output_dir)

        for inventory_offset, listing in enumerate(selected, start=offset):
            try:
                if execute:
                    client.delete_managed_listing(listing.listing_id)
                    mutation_status = MutationStatus.SUCCEEDED
                else:
                    mutation_status = MutationStatus.PLANNED
                record = _record(inventory_offset, listing, mutation_status)
            except (KeyboardInterrupt, Exception) as error:
                message = _safe_error(error)
                failed_record = _record(
                    inventory_offset,
                    listing,
                    MutationStatus.FAILED,
                    mutation_error=message,
                )
                run.records.append(failed_record)
                run.error = message
                _checkpoint(run, client, output_dir)
                output(_format_record(failed_record, use_color=use_color))
                break

            run.records.append(record)
            run.completed_listing_count += 1
            run.next_offset = offset + run.completed_listing_count
            _checkpoint(run, client, output_dir)
            output(_format_record(record, use_color=use_color))
            output(f"[progress] next offset: {run.next_offset}")
        else:
            run.complete = True
            run.portfolio_complete = run.next_offset >= len(managed_listings)

        return run
    finally:
        try:
            _checkpoint(run, client, output_dir)
        finally:
            if not run.portfolio_complete:
                output(
                    "[resume] command: "
                    + _continuation_command(
                        run.next_offset,
                        limit,
                        execute=execute,
                    )
                )
            output(f"[resume] next offset: {run.next_offset}")


def write_reports(run, output_dir, *, updated_at=None):
    updated_at = updated_at or datetime.now(timezone.utc)
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    record_payloads = [_record_payload(record) for record in run.records]
    report = {
        "schema_version": REPORT_SCHEMA_VERSION,
        "generated_at": run.generated_at.astimezone(timezone.utc).isoformat(),
        "updated_at": updated_at.astimezone(timezone.utc).isoformat(),
        "execution_mode": run.execution_mode,
        "requested_offset": run.requested_offset,
        "requested_limit": run.requested_limit,
        "managed_listing_count": run.managed_listing_count,
        "selected_listing_count": run.selected_listing_count,
        "completed_listing_count": run.completed_listing_count,
        "next_offset": run.next_offset,
        "request_count": run.request_count,
        "complete": run.complete,
        "portfolio_complete": run.portfolio_complete,
        "error": run.error,
        "records": record_payloads,
    }

    csv_path = output_dir / "listings.csv"
    csv_tmp_path = output_dir / "listings.csv.tmp"
    with csv_tmp_path.open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=CSV_FIELDS)
        writer.writeheader()
        for payload in record_payloads:
            row = dict(payload)
            for field in CSV_FIELDS:
                if row[field] is None:
                    row[field] = ""
            writer.writerow(row)
    csv_tmp_path.replace(csv_path)

    _atomic_write_text(
        output_dir / "report.json",
        json.dumps(report, indent=2, sort_keys=True) + "\n",
    )
    return report


def default_output_dir(generated_at=None):
    generated_at = generated_at or datetime.now(timezone.utc)
    workspace = os.environ.get("BUILD_WORKSPACE_DIRECTORY")
    base = Path(workspace) if workspace else Path.cwd()
    timestamp = generated_at.astimezone(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return base / "tmp" / "tcg-lister" / f"delete-{timestamp}"


def main(argv: Sequence[str] | None = None):
    args = parse_args(argv)
    generated_at = datetime.now(timezone.utc)
    output_dir = default_output_dir(generated_at)
    output_dir.mkdir(parents=True, exist_ok=True)
    client = FetchTcgClient(
        token=os.environ.get("FETCHTCG_TOKEN"),
        verbose=args.verbose,
    )
    previous_handlers = {}
    for signal_number in (signal.SIGINT, signal.SIGTERM):
        previous_handlers[signal_number] = signal.getsignal(signal_number)
        signal.signal(signal_number, _handle_termination_signal)
    try:
        run = run_deletes(
            client,
            output_dir=output_dir,
            offset=args.offset,
            limit=args.limit,
            execute=args.execute,
            generated_at=generated_at,
            use_color=sys.stdout.isatty(),
        )
    finally:
        for signal_number, previous_handler in previous_handlers.items():
            signal.signal(signal_number, previous_handler)

    print(_format_summary(run))
    print(f"Reports: {output_dir}")
    return 0 if run.complete else 1


def _record(inventory_offset, listing, mutation_status, *, mutation_error=None):
    return DeleteRecord(
        inventory_offset=inventory_offset,
        stable_position=inventory_offset + 1,
        listing_id=listing.listing_id,
        fetch_card_id=listing.fetch_card_id,
        scryfall_id=listing.scryfall_id,
        name=listing.name,
        collector_number=listing.collector_number,
        set_id=listing.set_id,
        set_name=listing.set_name,
        finish=listing.finish,
        condition=listing.condition,
        remaining_quantity=listing.remaining_quantity,
        listed_price_nzd=listing.listed_price_nzd,
        mutation_status=mutation_status,
        mutation_error=mutation_error,
    )


def _record_payload(record):
    return {
        "inventory_offset": record.inventory_offset,
        "stable_position": record.stable_position,
        "listing_id": record.listing_id,
        "fetch_card_id": record.fetch_card_id,
        "scryfall_id": record.scryfall_id,
        "name": record.name,
        "collector_number": record.collector_number,
        "set_id": record.set_id,
        "set_name": record.set_name,
        "finish": record.finish,
        "condition": record.condition,
        "remaining_quantity": record.remaining_quantity,
        "listed_price_nzd": f"{record.listed_price_nzd:.2f}",
        "mutation_status": record.mutation_status.value,
        "mutation_error": record.mutation_error,
    }


def _checkpoint(run, client, output_dir):
    run.request_count = client.request_count
    write_reports(run, output_dir)


def _atomic_write_text(path, content):
    path = Path(path)
    temporary = path.with_name(f"{path.name}.tmp")
    temporary.write_text(content)
    temporary.replace(path)


def _safe_error(error):
    message = str(error) or error.__class__.__name__
    token = os.environ.get("FETCHTCG_TOKEN")
    if token:
        message = message.replace(token, "[redacted]")
    return message


def _format_record(record, *, use_color=False):
    status = record.mutation_status.value
    formatted_status = (
        f"{STATUS_COLORS[status]}{status}{ANSI_RESET}" if use_color else status
    )
    line = (
        f"[{record.stable_position}] #{record.listing_id} {record.name}: "
        f"{record.condition} x{record.remaining_quantity} "
        f"NZ${record.listed_price_nzd:.2f} {formatted_status}"
    )
    if record.mutation_error is not None:
        line += f" — {record.mutation_error}"
    return line


def _format_summary(run):
    return (
        f"[summary] completed {run.completed_listing_count}/"
        f"{run.selected_listing_count} selected listings; "
        f"next offset {run.next_offset}; "
        f"{'complete' if run.complete else 'incomplete'}"
    )


def _continuation_command(offset, limit, *, execute):
    arguments = [f"--offset {offset}"]
    if limit is not None:
        arguments.append(f"--limit {limit}")
    if execute:
        arguments.append("--execute")
    return "bazel run //tcg_lister_api:fetchtcg-delete-listings -- " + " ".join(
        arguments
    )


def _handle_termination_signal(signum, _frame):
    signal_name = signal.Signals(signum).name
    raise ControlledTermination(f"received {signal_name}")


def _nonnegative_int(value):
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be an integer") from error
    if parsed < 0:
        raise argparse.ArgumentTypeError("must be at least 0")
    return parsed


def _positive_int(value):
    parsed = _nonnegative_int(value)
    if parsed == 0:
        raise argparse.ArgumentTypeError("must be greater than 0")
    return parsed


if __name__ == "__main__":
    sys.exit(main())
