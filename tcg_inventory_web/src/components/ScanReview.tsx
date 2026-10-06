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
import { apiClient } from '../api/client';
import type {
  CatalogCard,
  CatalogCardsResponse,
  ScanConfirmationRow,
  ScanDetail,
  ScanRow,
} from '../api/client';
import { useGames } from '../GamesProvider';
import classes from './ScanReview.module.css';
import { ScanReviewPanels, type ReviewSelection } from './ScanReviewPanels';

interface ScanReviewProps {
  scan: ScanDetail;
  confirmationPending: boolean;
  onDeleteRow: (scanPosition: number) => Promise<void>;
  onConfirmScan: (rows: ScanConfirmationRow[]) => Promise<void>;
}

interface AlternativesState extends CatalogCardsResponse {
  anchorExternalId: string;
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
  const { getFinish } = useGames();
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
          <Badge variant="light">{getFinish(scan.game, scan.finish)}</Badge>
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
        {scan.error && (
          <Text
            c={scan.status === 'reviewing' ? 'orange.8' : 'red.7'}
            size="sm"
            role="alert"
          >
            {scan.error}
          </Text>
        )}
      </Stack>
    </Paper>
  );
}

export function ScanReview({
  scan,
  confirmationPending,
  onDeleteRow,
  onConfirmScan,
}: ScanReviewProps) {
  const { getFinish, getGame } = useGames();
  const game = getGame(scan.game);
  const finishName = getFinish(scan.game, scan.finish);
  const rows = [...scan.rows].sort(
    (left, right) => left.scan_position - right.scan_position,
  );
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [selections, setSelections] = useState<Map<number, ReviewSelection>>(
    () => new Map(),
  );
  const [alternativesByPosition, setAlternativesByPosition] = useState<
    Map<number, AlternativesState>
  >(() => new Map());
  const [loadingPositions, setLoadingPositions] = useState<Set<number>>(
    () => new Set(),
  );
  const [loadingMorePositions, setLoadingMorePositions] = useState<Set<number>>(
    () => new Set(),
  );
  const [rowErrors, setRowErrors] = useState<Map<number, string>>(
    () => new Map(),
  );
  const [search, setSearch] = useState('');
  const [searchResults, setSearchResults] = useState<CatalogCard[]>([]);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [searchSelectionLoading, setSearchSelectionLoading] = useState(false);
  const [deleteLoading, setDeleteLoading] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [confirmError, setConfirmError] = useState<string | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const rowRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const catalogLookupVersions = useRef(new Map<number, number>());
  const alternativesAnchorVersions = useRef(new Map<number, number>());
  const searchRequestVersion = useRef(0);
  const selectedRow = rows[selectedIndex];
  const selectedPosition = selectedRow?.scan_position;
  const selectedSuggestion = selectedRow
    ? highestSuggestion(selectedRow)
    : undefined;
  const selectedSuggestionId = selectedSuggestion?.external_id;
  const selectedSelection =
    selectedPosition === undefined
      ? undefined
      : selections.get(selectedPosition);
  const alternativesState =
    selectedPosition === undefined
      ? undefined
      : alternativesByPosition.get(selectedPosition);
  const products = alternativesState?.cards ?? [];
  const selectedProductIsListed =
    selectedSelection &&
    products.some(
      (card) => card.external_id === selectedSelection.card.external_id,
    );
  const selectedProducts =
    selectedSelection && !selectedProductIsListed
      ? [selectedSelection.card, ...products]
      : products;
  const selectedProductIndex = selectedSelection
    ? selectedProducts.findIndex(
        (card) => card.external_id === selectedSelection.card.external_id,
      )
    : -1;
  const selectedFinishAvailable =
    selectedSelection?.card.available_finishes.includes(scan.finish) ?? false;
  const confirmedCount = rows.filter((row) => {
    const selection = selections.get(row.scan_position);
    return (
      selection?.confirmed === true &&
      selection.card.available_finishes.includes(scan.finish)
    );
  }).length;
  const canConfirm =
    rows.length > 0 &&
    confirmedCount === rows.length &&
    !deleteLoading &&
    !confirmationPending;

  useEffect(() => {
    if (selectedPosition === undefined || selectedSuggestionId === undefined) {
      return;
    }
    const suggestionId = selectedSuggestionId;
    if (selections.has(selectedPosition)) {
      return;
    }
    let cancelled = false;
    const requestVersion =
      (catalogLookupVersions.current.get(selectedPosition) ?? 0) + 1;
    const anchorVersion =
      (alternativesAnchorVersions.current.get(selectedPosition) ?? 0) + 1;
    catalogLookupVersions.current.set(selectedPosition, requestVersion);
    alternativesAnchorVersions.current.set(selectedPosition, anchorVersion);
    setLoadingPositions((previous) => new Set(previous).add(selectedPosition));
    setRowErrors((previous) => {
      const next = new Map(previous);
      next.delete(selectedPosition);
      return next;
    });

    async function loadSuggestedProduct() {
      try {
        const card = await apiClient.getCatalogCard(scan.game, suggestionId);
        if (
          cancelled ||
          catalogLookupVersions.current.get(selectedPosition) !== requestVersion
        ) {
          return;
        }
        setSelections((previous) =>
          new Map(previous).set(selectedPosition, { card, confirmed: false }),
        );

        const response = await apiClient.findCatalogAlternatives({
          game: scan.game,
          external_id: suggestionId,
          finish: scan.finish,
        });
        if (
          cancelled ||
          catalogLookupVersions.current.get(selectedPosition) !==
            requestVersion ||
          alternativesAnchorVersions.current.get(selectedPosition) !==
            anchorVersion
        ) {
          return;
        }
        setAlternativesByPosition((previous) =>
          new Map(previous).set(selectedPosition, {
            ...response,
            anchorExternalId: suggestionId,
          }),
        );
      } catch (error: unknown) {
        if (
          !cancelled &&
          catalogLookupVersions.current.get(selectedPosition) === requestVersion
        ) {
          setRowErrors((previous) =>
            new Map(previous).set(
              selectedPosition,
              error instanceof Error ? error.message : 'Card lookup failed',
            ),
          );
        }
      } finally {
        if (
          catalogLookupVersions.current.get(selectedPosition) === requestVersion
        ) {
          setLoadingPositions((previous) => {
            const next = new Set(previous);
            next.delete(selectedPosition);
            return next;
          });
        }
      }
    }

    void loadSuggestedProduct();

    return () => {
      cancelled = true;
    };
  }, [scan.finish, scan.game, selectedPosition, selectedSuggestionId]);

  useEffect(() => {
    if (selectedIndex >= rows.length) {
      setSelectedIndex(Math.max(rows.length - 1, 0));
    }
    rowRefs.current[selectedIndex]?.scrollIntoView({ block: 'nearest' });
  }, [rows.length, selectedIndex]);

  useEffect(() => {
    searchRequestVersion.current += 1;
    setSearch('');
    setSearchResults([]);
    setSearchError(null);
    setSearchSelectionLoading(false);
  }, [selectedPosition]);

  useEffect(() => {
    const query = search.trim();
    const requestVersion = ++searchRequestVersion.current;
    setSearchResults([]);
    setSearchError(null);
    setSearching(false);
    if (query.length < 2) {
      return;
    }

    async function searchCatalog() {
      setSearching(true);
      setSearchError(null);
      try {
        const response = await apiClient.findCatalogCards({
          game: scan.game,
          query,
          finish: scan.finish,
        });
        if (searchRequestVersion.current === requestVersion) {
          setSearchResults(response.cards);
        }
      } catch (error: unknown) {
        if (searchRequestVersion.current === requestVersion) {
          setSearchError(
            error instanceof Error ? error.message : 'Card search failed',
          );
        }
      } finally {
        if (searchRequestVersion.current === requestVersion) {
          setSearching(false);
        }
      }
    }

    const timer = window.setTimeout(() => {
      void searchCatalog();
    }, 250);

    return () => {
      window.clearTimeout(timer);
      if (searchRequestVersion.current === requestVersion) {
        searchRequestVersion.current += 1;
      }
    };
  }, [scan.finish, scan.game, search]);

  const moveRow = (change: number) => {
    setSelectedIndex((index) =>
      Math.max(0, Math.min(rows.length - 1, index + change)),
    );
  };

  const invalidateCatalogLookup = (position: number) => {
    catalogLookupVersions.current.set(
      position,
      (catalogLookupVersions.current.get(position) ?? 0) + 1,
    );
    setLoadingPositions((previous) => {
      const next = new Set(previous);
      next.delete(position);
      return next;
    });
  };

  const chooseProduct = (card: CatalogCard) => {
    if (selectedPosition === undefined) {
      return;
    }
    const current = selections.get(selectedPosition);
    if (current?.card.external_id === card.external_id) {
      return;
    }
    invalidateCatalogLookup(selectedPosition);
    setSelections((previous) =>
      new Map(previous).set(selectedPosition, { card, confirmed: false }),
    );
    setRowErrors((previous) => {
      const next = new Map(previous);
      next.delete(selectedPosition);
      return next;
    });
  };

  const moveProduct = (change: number) => {
    if (!selectedSelection) {
      return;
    }
    const nextIndex = Math.max(
      0,
      Math.min(
        selectedProducts.length - 1,
        Math.max(selectedProductIndex, 0) + change,
      ),
    );
    const nextProduct = selectedProducts[nextIndex];
    if (
      nextProduct &&
      nextProduct.external_id !== selectedSelection.card.external_id
    ) {
      chooseProduct(nextProduct);
    }
  };

  const confirmCurrent = () => {
    if (
      !selectedRow ||
      !selectedSelection ||
      selectedSelection.confirmed ||
      !selectedFinishAvailable
    ) {
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
    if (deleteLoading || confirming || confirmationPending) {
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
      invalidateCatalogLookup(position);
      alternativesAnchorVersions.current.set(
        position,
        (alternativesAnchorVersions.current.get(position) ?? 0) + 1,
      );
      setSelections((previous) => {
        const next = new Map(previous);
        next.delete(position);
        return next;
      });
      setAlternativesByPosition((previous) => {
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

  const selectSearchResult = async (card: CatalogCard) => {
    if (selectedPosition === undefined) {
      return;
    }
    const position = selectedPosition;
    const lookupVersion =
      (catalogLookupVersions.current.get(position) ?? 0) + 1;
    const anchorVersion =
      (alternativesAnchorVersions.current.get(position) ?? 0) + 1;
    catalogLookupVersions.current.set(position, lookupVersion);
    alternativesAnchorVersions.current.set(position, anchorVersion);
    setLoadingPositions((previous) => new Set(previous).add(position));
    setSearchSelectionLoading(true);
    setSearchError(null);
    setSelections((previous) =>
      new Map(previous).set(position, { card, confirmed: false }),
    );
    setAlternativesByPosition((previous) =>
      new Map(previous).set(position, {
        anchorExternalId: card.external_id,
        cards: [],
        next_continuation: null,
      }),
    );
    setRowErrors((previous) => {
      const next = new Map(previous);
      next.delete(position);
      return next;
    });
    searchRequestVersion.current += 1;
    setSearch('');
    setSearchResults([]);

    try {
      const response = await apiClient.findCatalogAlternatives({
        game: scan.game,
        external_id: card.external_id,
        finish: scan.finish,
      });
      if (
        catalogLookupVersions.current.get(position) === lookupVersion &&
        alternativesAnchorVersions.current.get(position) === anchorVersion
      ) {
        setAlternativesByPosition((previous) =>
          new Map(previous).set(position, {
            ...response,
            anchorExternalId: card.external_id,
          }),
        );
      }
    } catch (error: unknown) {
      if (catalogLookupVersions.current.get(position) === lookupVersion) {
        setRowErrors((previous) =>
          new Map(previous).set(
            position,
            error instanceof Error ? error.message : 'Card lookup failed',
          ),
        );
      }
    } finally {
      if (catalogLookupVersions.current.get(position) === lookupVersion) {
        setLoadingPositions((previous) => {
          const next = new Set(previous);
          next.delete(position);
          return next;
        });
        setSearchSelectionLoading(false);
      }
    }
  };

  const loadMoreProducts = async () => {
    if (
      selectedPosition === undefined ||
      !alternativesState?.next_continuation ||
      loadingMorePositions.has(selectedPosition)
    ) {
      return;
    }
    const position = selectedPosition;
    const anchorExternalId = alternativesState.anchorExternalId;
    const anchorVersion = alternativesAnchorVersions.current.get(position);
    setLoadingMorePositions((previous) => new Set(previous).add(position));
    try {
      const response = await apiClient.findCatalogAlternatives({
        game: scan.game,
        external_id: anchorExternalId,
        finish: scan.finish,
        continuation: alternativesState.next_continuation,
      });
      if (alternativesAnchorVersions.current.get(position) === anchorVersion) {
        setAlternativesByPosition((previous) => {
          const current = previous.get(position);
          if (current?.anchorExternalId !== anchorExternalId) {
            return previous;
          }
          return new Map(previous).set(position, {
            ...current,
            cards: [...current.cards, ...response.cards],
            next_continuation: response.next_continuation,
          });
        });
      }
    } catch (error: unknown) {
      if (alternativesAnchorVersions.current.get(position) === anchorVersion) {
        setRowErrors((previous) =>
          new Map(previous).set(
            position,
            error instanceof Error ? error.message : 'Card lookup failed',
          ),
        );
      }
    } finally {
      setLoadingMorePositions((previous) => {
        const next = new Set(previous);
        next.delete(position);
        return next;
      });
    }
  };

  const confirmScan = async () => {
    if (!canConfirm || confirming || confirmationPending) {
      return;
    }
    const confirmationRows: ScanConfirmationRow[] = rows.map((row) => {
      const selection = selections.get(row.scan_position)!;
      return {
        scan_position: row.scan_position,
        external_id: selection.card.external_id,
        name: selection.card.name,
        set_code: selection.card.set_code,
        set_name: selection.card.set_name,
        collector_number: selection.card.collector_number,
      };
    });

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

  useEffect(() => {
    if (confirmationPending) {
      return;
    }
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
          moveProduct(-1);
          break;
        case 'l':
          event.preventDefault();
          moveProduct(1);
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

  if (confirmationPending) {
    return (
      <Paper
        component="section"
        aria-label="Scan confirmation"
        aria-busy="true"
        withBorder
        radius="md"
        p="md"
      >
        <Stack gap="sm">
          <Group justify="space-between" gap="sm">
            <Text fw={600} size="sm">
              Confirming scan
            </Text>
            <Badge variant="light" color="blue">
              confirming
            </Badge>
          </Group>
          <Text size="sm" c="dimmed">
            Creating an import from the selected printings.
          </Text>
          <Progress
            value={100}
            animated
            aria-label="Scan confirmation progress"
          />
        </Stack>
      </Paper>
    );
  }

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
              const rowConfirmed =
                rowSelection?.confirmed === true &&
                rowSelection.card.available_finishes.includes(scan.finish);
              const attention =
                row.needs_review ||
                row.error !== null ||
                rowSuggestion === undefined ||
                rowErrors.has(row.scan_position) ||
                (rowSelection !== undefined &&
                  !rowSelection.card.available_finishes.includes(scan.finish));
              const rowName =
                rowSelection?.card.name ??
                rowSuggestion?.name ??
                'Manual selection required';
              const rowMetadata = rowSelection
                ? `${rowSelection.card.set_code.toUpperCase()} · ${rowSelection.card.collector_number}`
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
                    className={`${classes.statusIcon} ${rowConfirmed ? classes.statusIconConfirmed : attention ? classes.statusIconAttention : rowSelection ? classes.statusIconSuggested : ''}`}
                  >
                    <QueueStatusIcon
                      confirmed={rowConfirmed}
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
                    {rowConfirmed
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
          products={selectedProducts}
          productIndex={selectedProductIndex}
          productContinuation={alternativesState?.next_continuation ?? null}
          loadingMoreProducts={
            selectedPosition !== undefined &&
            loadingMorePositions.has(selectedPosition)
          }
          loading={
            selectedPosition !== undefined &&
            loadingPositions.has(selectedPosition)
          }
          error={selectedError}
          finish={scan.finish}
          finishName={finishName}
          finishAvailable={selectedFinishAvailable}
          imageRegions={game.scan_review_image_regions}
          searchRef={searchRef}
          search={search}
          searchResults={searchResults}
          searching={searching}
          searchError={searchError}
          searchSelectionLoading={searchSelectionLoading}
          onMoveRow={moveRow}
          onMoveProduct={moveProduct}
          onLoadMoreProducts={() => void loadMoreProducts()}
          onChooseProduct={chooseProduct}
          onSearchChange={(value) => {
            setSearch(value);
            setSearchResults([]);
            setSearchError(null);
            setSearching(false);
          }}
          onSearchResult={(card) => void selectSearchResult(card)}
          onConfirm={confirmCurrent}
          onDelete={() => {
            setDeleteError(null);
            if (selectedPosition !== undefined) {
              void deleteCurrentRow(selectedPosition);
            }
          }}
          deleteDisabled={confirming || deleteLoading}
          controlsDisabled={confirming || deleteLoading}
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
