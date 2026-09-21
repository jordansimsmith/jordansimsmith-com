import { useEffect, useRef, useState } from 'react';
import {
  Badge,
  Box,
  Button,
  Group,
  Paper,
  Progress,
  Stack,
  Text,
} from '@mantine/core';
import {
  IconAlertCircle,
  IconCircleCheck,
  IconCircleDashed,
  IconSparkles,
} from '@tabler/icons-react';
import { scryfallClient } from '../api/scryfall-client';
import type { ScryfallPrinting } from '../api/scryfall-client';
import type { ScanConfirmationRow, ScanDetail, ScanRow } from '../api/client';
import classes from './ScanReview.module.css';
import { ScanReviewPanels, type ReviewSelection } from './ScanReviewPanels';

interface ScanReviewProps {
  scan: ScanDetail;
  onDeleteRow: (scanPosition: number) => Promise<void>;
  onConfirmScan: (rows: ScanConfirmationRow[]) => Promise<void>;
}

function formatFinish(finish: ScanDetail['finish']): string {
  switch (finish) {
    case 'normal':
      return 'Normal';
    case 'foil':
      return 'Foil';
    case 'etched':
      return 'Etched';
  }
}

function highestSuggestion(row: ScanRow) {
  return [...row.suggestions].sort(
    (left, right) => right.score - left.score,
  )[0];
}

function QueueStatusIcon({
  confirmed,
  attention,
  loaded,
}: {
  confirmed: boolean;
  attention: boolean;
  loaded: boolean;
}) {
  const Icon = confirmed
    ? IconCircleCheck
    : attention
      ? IconAlertCircle
      : loaded
        ? IconSparkles
        : IconCircleDashed;
  return <Icon size={15} stroke={1.8} aria-hidden="true" />;
}

function ReviewSummary({
  scan,
  confirmedCount,
  rowCount,
  canConfirm,
  confirming,
  confirmError,
  onConfirm,
}: {
  scan: ScanDetail;
  confirmedCount: number;
  rowCount: number;
  canConfirm: boolean;
  confirming: boolean;
  confirmError: string | null;
  onConfirm: () => void;
}) {
  const progress = rowCount === 0 ? 0 : (confirmedCount / rowCount) * 100;

  return (
    <Paper
      component="section"
      aria-label="Scan summary"
      withBorder
      radius="md"
      p="md"
    >
      <Stack gap="md">
        <Group justify="space-between" gap="sm" wrap="wrap">
          <Group gap="sm">
            <Text fw={600} size="sm">
              Scan summary
            </Text>
            <Badge variant="light" color="yellow">
              review
            </Badge>
          </Group>
          <Text size="sm" c="dimmed">
            {scan.row_count} {scan.row_count === 1 ? 'card' : 'cards'}
          </Text>
        </Group>
        <Group gap="sm">
          <Badge variant="light">{scan.condition}</Badge>
          <Badge variant="light">{formatFinish(scan.finish)}</Badge>
        </Group>
        <Stack gap="xs">
          <Group justify="space-between" gap="sm" wrap="wrap">
            <Text size="sm">
              {confirmedCount} of {rowCount} confirmed
            </Text>
            <Button
              color="teal"
              loading={confirming}
              disabled={!canConfirm || confirming}
              onClick={onConfirm}
            >
              Confirm scan
            </Button>
          </Group>
          <Progress
            value={progress}
            size="sm"
            color="teal"
            aria-label="Review confirmation progress"
          />
        </Stack>
        {confirmError && (
          <Text size="sm" c="red.7" role="alert">
            {confirmError}
          </Text>
        )}
        <Text size="sm" c="dimmed">
          Identification is complete. This scan is ready for review.
        </Text>
        {scan.error && (
          <Text c="red.7" size="sm" role="alert">
            {scan.error}
          </Text>
        )}
      </Stack>
    </Paper>
  );
}

export function ScanReview({
  scan,
  onDeleteRow,
  onConfirmScan,
}: ScanReviewProps) {
  const rows = [...scan.rows].sort(
    (left, right) => left.scan_position - right.scan_position,
  );
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [selections, setSelections] = useState<Map<number, ReviewSelection>>(
    () => new Map(),
  );
  const [printingsByPosition, setPrintingsByPosition] = useState<
    Map<number, ScryfallPrinting[]>
  >(() => new Map());
  const [loadingPositions, setLoadingPositions] = useState<Set<number>>(
    () => new Set(),
  );
  const [rowErrors, setRowErrors] = useState<Map<number, string>>(
    () => new Map(),
  );
  const [search, setSearch] = useState('');
  const [searchSuggestions, setSearchSuggestions] = useState<string[]>([]);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [searchSelectionLoading, setSearchSelectionLoading] = useState(false);
  const [deleteLoading, setDeleteLoading] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [confirmError, setConfirmError] = useState<string | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const rowRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const suggestionRequestVersions = useRef(new Map<number, number>());
  const selectedRow = rows[selectedIndex];
  const selectedPosition = selectedRow?.scan_position;
  const selectedSuggestion = selectedRow
    ? highestSuggestion(selectedRow)
    : undefined;
  const selectedSuggestionId = selectedSuggestion?.scryfall_id;
  const selectedSelection =
    selectedPosition === undefined
      ? undefined
      : selections.get(selectedPosition);
  const selectedPrintings =
    selectedPosition === undefined
      ? []
      : (printingsByPosition.get(selectedPosition) ?? []);
  const selectedPrintingIndex = selectedSelection
    ? selectedPrintings.findIndex(
        (printing) => printing.id === selectedSelection.printing.id,
      )
    : -1;
  const confirmedCount = rows.filter(
    (row) => selections.get(row.scan_position)?.confirmed === true,
  ).length;
  const canConfirm =
    rows.length > 0 && confirmedCount === rows.length && !deleteLoading;

  useEffect(() => {
    if (selectedPosition === undefined || selectedSuggestionId === undefined) {
      return;
    }
    if (selections.has(selectedPosition) || rowErrors.has(selectedPosition)) {
      return;
    }

    let cancelled = false;
    const requestVersion =
      (suggestionRequestVersions.current.get(selectedPosition) ?? 0) + 1;
    suggestionRequestVersions.current.set(selectedPosition, requestVersion);
    setLoadingPositions((previous) => new Set(previous).add(selectedPosition));
    void scryfallClient
      .getPrintingsForId(selectedSuggestionId)
      .then((printings) => {
        if (
          cancelled ||
          suggestionRequestVersions.current.get(selectedPosition) !==
            requestVersion
        ) {
          return;
        }
        const selectedPrinting =
          printings.find((printing) => printing.id === selectedSuggestionId) ??
          printings[0];
        if (!selectedPrinting) {
          throw new Error('No printings were found for this suggestion');
        }
        setPrintingsByPosition((previous) =>
          new Map(previous).set(selectedPosition, printings),
        );
        setSelections((previous) =>
          new Map(previous).set(selectedPosition, {
            printing: selectedPrinting,
            confirmed: false,
          }),
        );
        setRowErrors((previous) => {
          const next = new Map(previous);
          next.delete(selectedPosition);
          return next;
        });
      })
      .catch((error: unknown) => {
        if (
          !cancelled &&
          suggestionRequestVersions.current.get(selectedPosition) ===
            requestVersion
        ) {
          setRowErrors((previous) =>
            new Map(previous).set(
              selectedPosition,
              error instanceof Error
                ? error.message.replace(/scryfall/gi, 'card lookup')
                : 'Card lookup failed',
            ),
          );
        }
      })
      .finally(() => {
        if (
          suggestionRequestVersions.current.get(selectedPosition) !==
          requestVersion
        ) {
          return;
        }
        setLoadingPositions((previous) => {
          const next = new Set(previous);
          next.delete(selectedPosition);
          return next;
        });
      });

    return () => {
      cancelled = true;
    };
  }, [selectedPosition, selectedSuggestionId]);

  useEffect(() => {
    if (selectedIndex >= rows.length) {
      setSelectedIndex(Math.max(rows.length - 1, 0));
    }
    rowRefs.current[selectedIndex]?.scrollIntoView({ block: 'nearest' });
  }, [rows.length, selectedIndex]);

  useEffect(() => {
    setSearch('');
    setSearchSuggestions([]);
    setSearchError(null);
  }, [selectedPosition]);

  useEffect(() => {
    if (search.trim().length < 2) {
      setSearchSuggestions([]);
      setSearchError(null);
      setSearching(false);
      return;
    }

    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      setSearching(true);
      setSearchError(null);
      void scryfallClient
        .autocomplete(search.trim(), controller.signal)
        .then((suggestions) => {
          if (!controller.signal.aborted) {
            setSearchSuggestions(suggestions);
          }
        })
        .catch((error: unknown) => {
          if (!controller.signal.aborted) {
            setSearchError(
              error instanceof Error
                ? error.message.replace(/scryfall/gi, 'card search')
                : 'Card search failed',
            );
          }
        })
        .finally(() => {
          if (!controller.signal.aborted) {
            setSearching(false);
          }
        });
    }, 250);

    return () => {
      controller.abort();
      window.clearTimeout(timer);
    };
  }, [search, selectedPosition]);

  const moveRow = (change: number) => {
    setSelectedIndex((index) =>
      Math.max(0, Math.min(rows.length - 1, index + change)),
    );
  };

  const movePrinting = (change: number) => {
    if (!selectedSelection || selectedPosition === undefined) {
      return;
    }
    const nextIndex = Math.max(
      0,
      Math.min(
        selectedPrintings.length - 1,
        Math.max(selectedPrintingIndex, 0) + change,
      ),
    );
    const nextPrinting = selectedPrintings[nextIndex];
    if (!nextPrinting || nextPrinting.id === selectedSelection.printing.id) {
      return;
    }
    invalidateSuggestionRequest(selectedPosition);
    setSelections((previous) =>
      new Map(previous).set(selectedPosition, {
        printing: nextPrinting,
        confirmed: false,
      }),
    );
  };

  const invalidateSuggestionRequest = (position: number) => {
    const nextVersion =
      (suggestionRequestVersions.current.get(position) ?? 0) + 1;
    suggestionRequestVersions.current.set(position, nextVersion);
    setLoadingPositions((previous) => {
      const next = new Set(previous);
      next.delete(position);
      return next;
    });
  };

  const choosePrinting = (printing: ScryfallPrinting) => {
    if (selectedPosition === undefined) {
      return;
    }
    const current = selections.get(selectedPosition);
    if (current?.printing.id === printing.id) {
      return;
    }
    invalidateSuggestionRequest(selectedPosition);
    setSelections((previous) =>
      new Map(previous).set(selectedPosition, {
        printing,
        confirmed: false,
      }),
    );
    setRowErrors((previous) => {
      const next = new Map(previous);
      next.delete(selectedPosition);
      return next;
    });
  };

  const confirmCurrent = () => {
    if (!selectedRow || !selectedSelection || selectedSelection.confirmed) {
      return;
    }
    setSelections((previous) =>
      new Map(previous).set(selectedRow.scan_position, {
        ...selectedSelection,
        confirmed: true,
      }),
    );
    const nextIndex = rows.findIndex(
      (row, index) =>
        index > selectedIndex &&
        selections.get(row.scan_position)?.confirmed !== true,
    );
    if (nextIndex >= 0) {
      setSelectedIndex(nextIndex);
    }
  };

  const deleteCurrentRow = async (position: number) => {
    if (deleteLoading || confirming) {
      return;
    }
    const deletedIndex = rows.findIndex(
      (row) => row.scan_position === position,
    );
    if (deletedIndex < 0) {
      return;
    }

    setDeleteLoading(true);
    setDeleteError(null);
    try {
      await onDeleteRow(position);
      invalidateSuggestionRequest(position);
      setSelections((previous) => {
        const next = new Map(previous);
        next.delete(position);
        return next;
      });
      setPrintingsByPosition((previous) => {
        const next = new Map(previous);
        next.delete(position);
        return next;
      });
      setRowErrors((previous) => {
        const next = new Map(previous);
        next.delete(position);
        return next;
      });
      setSelectedIndex((index) =>
        Math.max(0, Math.min(index, rows.length - 2)),
      );
    } catch (error: unknown) {
      setDeleteError(
        error instanceof Error ? error.message : 'Card deletion failed',
      );
    } finally {
      setDeleteLoading(false);
    }
  };

  const confirmScan = async () => {
    if (!canConfirm || confirming) {
      return;
    }
    const confirmationRows: ScanConfirmationRow[] = [];
    for (const row of rows) {
      const selection = selections.get(row.scan_position);
      if (!selection?.confirmed) {
        return;
      }
      confirmationRows.push({
        scan_position: row.scan_position,
        scryfall_id: selection.printing.id,
        name: selection.printing.name,
        set_code: selection.printing.set_code,
        set_name: selection.printing.set_name,
        collector_number: selection.printing.collector_number,
        confirmed: true as const,
      });
    }

    setConfirming(true);
    setConfirmError(null);
    try {
      await onConfirmScan(confirmationRows);
    } catch (error: unknown) {
      setConfirmError(
        error instanceof Error ? error.message : 'Scan confirmation failed',
      );
    } finally {
      setConfirming(false);
    }
  };

  const selectSearchResult = async (name: string) => {
    if (selectedPosition === undefined) {
      return;
    }
    invalidateSuggestionRequest(selectedPosition);
    setSearchSelectionLoading(true);
    setSearchError(null);
    try {
      const printings = await scryfallClient.getPrintingsByName(name);
      if (printings.length === 0) {
        throw new Error('No printings were found for that card');
      }
      setPrintingsByPosition((previous) =>
        new Map(previous).set(selectedPosition, printings),
      );
      choosePrinting(printings[0]);
      setSearch('');
      setSearchSuggestions([]);
    } catch (error: unknown) {
      setSearchError(
        error instanceof Error
          ? error.message.replace(/scryfall/gi, 'card search')
          : 'Card search failed',
      );
    } finally {
      setSearchSelectionLoading(false);
    }
  };

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        if (event.target instanceof HTMLElement) {
          event.target.blur();
        }
        searchRef.current?.blur();
        return;
      }
      const target = event.target;
      if (
        target instanceof HTMLInputElement ||
        target instanceof HTMLTextAreaElement ||
        target instanceof HTMLSelectElement ||
        (target instanceof HTMLElement && target.isContentEditable)
      ) {
        return;
      }
      if (event.metaKey || event.ctrlKey || event.altKey) {
        return;
      }
      if (confirming || deleteLoading) {
        return;
      }

      switch (event.key) {
        case '/':
          event.preventDefault();
          searchRef.current?.focus();
          searchRef.current?.select();
          break;
        case 'j':
          event.preventDefault();
          moveRow(1);
          break;
        case 'k':
          event.preventDefault();
          moveRow(-1);
          break;
        case 'h':
          event.preventDefault();
          movePrinting(-1);
          break;
        case 'l':
          event.preventDefault();
          movePrinting(1);
          break;
        case 'c':
          event.preventDefault();
          confirmCurrent();
          break;
      }
    };

    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  });

  if (!selectedRow) {
    return (
      <Stack gap="md" data-scan-review data-scan-review-editable>
        <ReviewSummary
          scan={scan}
          confirmedCount={confirmedCount}
          rowCount={rows.length}
          canConfirm={canConfirm}
          confirming={confirming}
          confirmError={confirmError}
          onConfirm={() => void confirmScan()}
        />
        <Paper withBorder radius="md" p="md">
          <Text>There are no scan rows available for review.</Text>
        </Paper>
      </Stack>
    );
  }

  const suggestion = selectedSuggestion;
  const selectedError =
    selectedPosition === undefined
      ? undefined
      : rowErrors.get(selectedPosition);

  return (
    <Stack gap="md" data-scan-review data-scan-review-editable>
      <ReviewSummary
        scan={scan}
        confirmedCount={confirmedCount}
        rowCount={rows.length}
        canConfirm={canConfirm}
        confirming={confirming}
        confirmError={confirmError}
        onConfirm={() => void confirmScan()}
      />

      <div className={classes.layout}>
        <Paper
          component="section"
          aria-label="Scan cards"
          withBorder
          radius="md"
          className={classes.queue}
        >
          <Box className={classes.queueHeader}>
            <Text fw={600} size="sm">
              Cards
            </Text>
          </Box>
          <Box className={classes.queueList}>
            {rows.map((row, index) => {
              const rowSelection = selections.get(row.scan_position);
              const rowSuggestion = highestSuggestion(row);
              const confirmed = rowSelection?.confirmed === true;
              const attention =
                row.needs_review ||
                row.error !== null ||
                rowSuggestion === undefined ||
                rowErrors.has(row.scan_position);
              const rowName =
                rowSelection?.printing.name ??
                rowSuggestion?.name ??
                'Manual selection required';
              const rowMetadata = rowSelection
                ? `${rowSelection.printing.set_code.toUpperCase()} · ${rowSelection.printing.collector_number}`
                : row.filename;
              return (
                <button
                  key={row.scan_position}
                  ref={(element) => {
                    rowRefs.current[index] = element;
                  }}
                  type="button"
                  aria-current={index === selectedIndex ? 'true' : undefined}
                  disabled={confirming || deleteLoading}
                  className={`${classes.queueItem} ${index === selectedIndex ? classes.queueItemSelected : ''}`}
                  onClick={() => setSelectedIndex(index)}
                >
                  <span
                    className={`${classes.statusIcon} ${confirmed ? classes.statusIconConfirmed : attention ? classes.statusIconAttention : rowSelection ? classes.statusIconSuggested : ''}`}
                  >
                    <QueueStatusIcon
                      confirmed={confirmed}
                      attention={attention}
                      loaded={
                        rowSelection !== undefined ||
                        rowSuggestion !== undefined
                      }
                    />
                  </span>
                  <span className={classes.queueItemText}>
                    <span className={classes.queueItemName}>
                      <strong>{row.scan_position}</strong> {rowName}
                    </span>
                    <small className={classes.queueItemMetadata}>
                      {rowMetadata}
                    </small>
                  </span>
                  <span className={classes.queueItemState}>
                    {confirmed
                      ? 'Confirmed'
                      : attention
                        ? 'Needs review'
                        : 'Suggested match'}
                  </span>
                </button>
              );
            })}
          </Box>
        </Paper>

        <ScanReviewPanels
          selectedRow={selectedRow}
          selectedIndex={selectedIndex}
          rowCount={rows.length}
          suggestion={suggestion}
          selection={selectedSelection}
          printings={selectedPrintings}
          printingIndex={selectedPrintingIndex}
          loading={
            selectedPosition !== undefined &&
            loadingPositions.has(selectedPosition)
          }
          error={selectedError}
          searchRef={searchRef}
          search={search}
          searchSuggestions={searchSuggestions}
          searching={searching}
          searchError={searchError}
          searchSelectionLoading={searchSelectionLoading}
          controlsDisabled={confirming || deleteLoading}
          onMoveRow={moveRow}
          onMovePrinting={movePrinting}
          onChoosePrinting={choosePrinting}
          onSearchChange={setSearch}
          onSearchResult={(name) => void selectSearchResult(name)}
          onConfirm={confirmCurrent}
          onDelete={() => {
            setDeleteError(null);
            if (selectedPosition !== undefined) {
              void deleteCurrentRow(selectedPosition);
            }
          }}
          deleteDisabled={confirming || deleteLoading}
        />
      </div>
      {deleteError && (
        <Text size="sm" c="red.7" role="alert">
          {deleteError}
        </Text>
      )}
    </Stack>
  );
}
