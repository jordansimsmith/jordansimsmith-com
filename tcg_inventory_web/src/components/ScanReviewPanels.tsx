import { useEffect, useState } from 'react';
import type { KeyboardEvent, RefObject } from 'react';
import {
  ActionIcon,
  Badge,
  Box,
  Button,
  Group,
  Image,
  Paper,
  Skeleton,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import {
  IconArrowLeft,
  IconArrowRight,
  IconCheck,
  IconChevronLeft,
  IconChevronRight,
  IconPhoto,
  IconSearch,
  IconTrash,
} from '@tabler/icons-react';
import type {
  CatalogCard,
  Finish,
  ScanReviewImageRegion,
  ScanRow,
  ScanSuggestion,
} from '../api/client';
import classes from './ScanReview.module.css';

export interface ReviewSelection {
  card: CatalogCard;
  confirmed: boolean;
}

interface ScanReviewPanelsProps {
  selectedRow: ScanRow;
  selectedIndex: number;
  rowCount: number;
  suggestion: ScanSuggestion | undefined;
  selection: ReviewSelection | undefined;
  products: CatalogCard[];
  productIndex: number;
  productContinuation: string | null;
  loadingMoreProducts: boolean;
  loading: boolean;
  error: string | undefined;
  finish: Finish;
  finishName: string;
  finishAvailable: boolean;
  imageRegions: ScanReviewImageRegion[];
  searchRef: RefObject<HTMLInputElement | null>;
  search: string;
  searchResults: CatalogCard[];
  searching: boolean;
  searchError: string | null;
  searchSelectionLoading: boolean;
  onMoveRow: (change: number) => void;
  onMoveProduct: (change: number) => void;
  onLoadMoreProducts: () => void;
  onChooseProduct: (card: CatalogCard) => void;
  onSearchChange: (value: string) => void;
  onSearchResult: (card: CatalogCard) => void;
  onConfirm: () => void;
  onDelete: () => void;
  deleteDisabled: boolean;
  controlsDisabled: boolean;
}

function Crop({
  image,
  region,
  label,
}: {
  image: string | null;
  region: ScanReviewImageRegion;
  label: string;
}) {
  const [failed, setFailed] = useState(false);
  const [naturalSize, setNaturalSize] = useState({ width: 0, height: 0 });

  useEffect(() => {
    setFailed(false);
    setNaturalSize({ width: 0, height: 0 });
  }, [image]);

  const aspectRatio =
    naturalSize.width > 0 && naturalSize.height > 0
      ? (region.width * naturalSize.width) /
        (region.height * naturalSize.height)
      : 25 / 14;

  return (
    <div className={classes.crop}>
      <div className={classes.cropViewport} style={{ aspectRatio }}>
        {!image || failed ? (
          <div className={classes.imageUnavailable}>Image unavailable</div>
        ) : (
          <img
            className={classes.cropImage}
            src={image}
            alt=""
            aria-hidden="true"
            style={{
              width: `${100 / region.width}%`,
              left: `${(-region.x / region.width) * 100}%`,
              top: `${(-region.y / region.height) * 100}%`,
            }}
            onLoad={(event) =>
              setNaturalSize({
                width: event.currentTarget.naturalWidth,
                height: event.currentTarget.naturalHeight,
              })
            }
            onError={() => setFailed(true)}
          />
        )}
      </div>
      <Text className={classes.cropLabel}>{label}</Text>
    </div>
  );
}

function ProductThumbnail({ card }: { card: CatalogCard }) {
  const [failed, setFailed] = useState(false);
  const image = card.image_urls.small ?? card.image_urls.normal;

  useEffect(() => {
    setFailed(false);
  }, [image]);

  if (!image || failed) {
    return (
      <span className={classes.productThumbnailFallback} aria-hidden="true">
        <IconPhoto size={16} />
      </span>
    );
  }

  return (
    <img
      className={classes.productThumbnail}
      src={image}
      alt=""
      aria-hidden="true"
      onError={() => setFailed(true)}
    />
  );
}

function ProductOption({
  card,
  finishName,
  finishAvailable,
}: {
  card: CatalogCard;
  finishName: string;
  finishAvailable: boolean;
}) {
  return (
    <>
      <ProductThumbnail card={card} />
      <span className={classes.productOptionDetails}>
        <strong className={classes.productOptionName}>{card.name}</strong>
        <small>
          {card.set_name} · {card.set_code.toUpperCase()} #
          {card.collector_number}
        </small>
        <small
          className={
            finishAvailable ? classes.finishEligible : classes.finishIneligible
          }
        >
          {finishName} {finishAvailable ? 'eligible' : 'not available'}
        </small>
      </span>
    </>
  );
}

function ScanImage({
  image,
  alt,
  source,
}: {
  image: string | null;
  alt: string;
  source?: boolean;
}) {
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    setFailed(false);
  }, [image]);

  if (!image || failed) {
    return (
      <Box
        className={classes.imageFrame}
        role="img"
        aria-label={`${alt} unavailable`}
      >
        <Group gap="xs" c="dimmed">
          <IconPhoto size={18} />
          <Text size="sm">Image unavailable</Text>
        </Group>
      </Box>
    );
  }

  return (
    <Box className={classes.imageFrame}>
      <Image
        src={image}
        alt={alt}
        className={`${classes.cardImage} ${source ? classes.sourceImage : ''}`}
        onError={() => setFailed(true)}
      />
    </Box>
  );
}

export function ScanReviewPanels({
  selectedRow,
  selectedIndex,
  rowCount,
  suggestion,
  selection,
  products,
  productIndex,
  productContinuation,
  loadingMoreProducts,
  loading,
  error,
  finish,
  finishName,
  finishAvailable,
  imageRegions,
  searchRef,
  search,
  searchResults,
  searching,
  searchError,
  searchSelectionLoading,
  onMoveRow,
  onMoveProduct,
  onLoadMoreProducts,
  onChooseProduct,
  onSearchChange,
  onSearchResult,
  onConfirm,
  onDelete,
  deleteDisabled,
  controlsDisabled,
}: ScanReviewPanelsProps) {
  const currentName =
    selection?.card.name ?? suggestion?.name ?? 'Manual selection required';
  const sourceImage = selectedRow.source_url;
  const candidateImage = selection?.card.image_urls.normal ?? null;
  const needsReview =
    selectedRow.needs_review ||
    selectedRow.error !== null ||
    suggestion === undefined ||
    error !== undefined ||
    (selection !== undefined && !finishAvailable);

  function selectTopSearchResult(event: KeyboardEvent<HTMLInputElement>) {
    const topResult = searchResults[0];
    if (event.key === 'Enter' && topResult) {
      event.preventDefault();
      onSearchResult(topResult);
    }
  }

  return (
    <Stack gap="sm" className={classes.reviewer}>
      <Paper withBorder radius="md" p="md">
        <Group justify="space-between" wrap="nowrap" gap="md">
          <Box>
            <Text size="xs" tt="uppercase" fw={700} c="dimmed">
              Card {selectedIndex + 1} of {rowCount}
            </Text>
            <Title order={3}>{currentName}</Title>
            <Text size="sm" c="dimmed">
              {selection
                ? `${selection.card.set_name} · ${selection.card.set_code.toUpperCase()} #${selection.card.collector_number}`
                : selectedRow.needs_review || selectedRow.error !== null
                  ? 'This card needs a manual printing choice.'
                  : suggestion
                    ? 'Suggested match · advisory only'
                    : 'Choose a product with card search.'}
            </Text>
            {selectedRow.error && (
              <Text size="sm" c="red.7" role="alert">
                {selectedRow.error}
              </Text>
            )}
          </Box>
          <Badge color={needsReview ? 'orange' : 'gray'} variant="light">
            {selection?.confirmed
              ? 'Confirmed'
              : needsReview
                ? 'Needs review'
                : 'Suggested match'}
          </Badge>
        </Group>
        {selection && (
          <Group gap="xs" mt="xs">
            <Badge color={finishAvailable ? 'teal' : 'orange'} variant="light">
              {finishAvailable
                ? `${finishName} available`
                : `${finishName} unavailable`}
            </Badge>
          </Group>
        )}
      </Paper>

      <div
        className={`${classes.comparison} ${imageRegions.length === 0 ? classes.comparisonWithoutRegions : ''}`}
      >
        <Paper withBorder radius="md" p="sm" className={classes.panel}>
          <Text fw={600} size="sm" mb="xs">
            Your scan
          </Text>
          <ScanImage
            image={sourceImage}
            alt={`Scanned ${selectedRow.filename}`}
            source
          />
        </Paper>
        <Paper
          withBorder
          radius="md"
          p="sm"
          className={`${classes.panel} ${classes.matchPanel}`}
        >
          <Group justify="space-between" mb="xs">
            <Text fw={600} size="sm">
              Selected product
            </Text>
            <Badge color="teal" variant="light">
              Reference
            </Badge>
          </Group>
          {loading && !selection ? (
            <Skeleton height={420} radius="sm" />
          ) : (
            <ScanImage
              image={candidateImage}
              alt={`${currentName} reference`}
            />
          )}
        </Paper>
        {imageRegions.length > 0 && (
          <Paper
            withBorder
            radius="md"
            p="sm"
            className={`${classes.panel} ${classes.verificationPanel}`}
          >
            <Text fw={600} size="sm" mb="xs">
              Print details
            </Text>
            <div className={classes.cropGroups}>
              {imageRegions.map((region) => (
                <div className={classes.cropPair} key={region.id}>
                  <Crop
                    image={sourceImage}
                    region={region}
                    label={`Your scan · ${region.display_name}`}
                  />
                  <Crop
                    image={candidateImage}
                    region={region}
                    label={`Reference · ${region.display_name}`}
                  />
                </div>
              ))}
            </div>
          </Paper>
        )}
      </div>

      <Paper withBorder radius="md" p="sm">
        <Group justify="space-between" mb="xs" wrap="wrap">
          <Box>
            <Text fw={600} size="sm">
              Other printings
            </Text>
            <Text size="xs" c="dimmed">
              Same card, eligible for {finishName} where available
            </Text>
          </Box>
          <Group gap={3}>
            <ActionIcon
              variant="default"
              aria-label="Previous printing"
              disabled={controlsDisabled || productIndex <= 0}
              onClick={() => onMoveProduct(-1)}
            >
              <IconChevronLeft size={16} />
            </ActionIcon>
            <Text size="sm" miw={38} ta="center">
              {productIndex < 0
                ? '—'
                : `${productIndex + 1} / ${products.length}`}
            </Text>
            <ActionIcon
              variant="default"
              aria-label="Next printing"
              disabled={
                controlsDisabled ||
                productIndex < 0 ||
                productIndex >= products.length - 1
              }
              onClick={() => onMoveProduct(1)}
            >
              <IconChevronRight size={16} />
            </ActionIcon>
          </Group>
        </Group>
        {products.length > 0 && (
          <div className={classes.printingTabs} aria-label="Printings">
            {products.map((card) => (
              <button
                type="button"
                key={card.external_id}
                aria-pressed={card.external_id === selection?.card.external_id}
                disabled={controlsDisabled}
                className={`${classes.printingButton} ${card.external_id === selection?.card.external_id ? classes.printingButtonSelected : ''}`}
                onClick={() => onChooseProduct(card)}
              >
                <ProductOption
                  card={card}
                  finishName={finishName}
                  finishAvailable={card.available_finishes.includes(finish)}
                />
              </button>
            ))}
          </div>
        )}
        {products.length === 0 && !loading && (
          <Text size="sm" c="dimmed">
            No eligible alternative printings found.
          </Text>
        )}
        {productContinuation && (
          <Button
            variant="default"
            size="xs"
            mt="xs"
            loading={loadingMoreProducts}
            disabled={controlsDisabled || loadingMoreProducts}
            onClick={onLoadMoreProducts}
          >
            Load more printings
          </Button>
        )}
        {error && (
          <Text size="sm" c="red.7" role="alert" mt="xs">
            {error}
          </Text>
        )}
      </Paper>

      <Paper withBorder radius="md" p="sm">
        <Text fw={600} size="sm">
          Wrong card?
        </Text>
        <Text size="xs" c="dimmed">
          Search the catalog, then compare the product’s other printings. Refine
          your search to narrow the results.
        </Text>
        <Stack gap={4} mt="sm">
          <TextInput
            ref={searchRef}
            aria-label="Search catalog cards"
            value={search}
            onChange={(event) => onSearchChange(event.currentTarget.value)}
            onKeyDown={selectTopSearchResult}
            placeholder="Search by card name or ID…"
            leftSection={<IconSearch size={16} />}
            disabled={controlsDisabled || searchSelectionLoading}
          />
          {searching && (
            <Text size="xs" c="dimmed">
              Searching catalog…
            </Text>
          )}
          {searchSelectionLoading && (
            <Text size="xs" c="dimmed">
              Loading related printings…
            </Text>
          )}
          {searchError && (
            <Text size="xs" c="red.7" role="alert">
              {searchError}
            </Text>
          )}
          {search.trim().length >= 2 &&
            !searching &&
            searchResults.length === 0 &&
            !searchError && (
              <Text size="xs" c="dimmed">
                No matching products with this finish.
              </Text>
            )}
          {searchResults.length > 0 && (
            <div className={classes.searchResults} aria-label="Catalog results">
              {searchResults.map((card) => (
                <button
                  type="button"
                  key={card.external_id}
                  className={classes.searchResult}
                  disabled={controlsDisabled || searchSelectionLoading}
                  onClick={() => onSearchResult(card)}
                >
                  <ProductOption
                    card={card}
                    finishName={finishName}
                    finishAvailable={card.available_finishes.includes(finish)}
                  />
                </button>
              ))}
            </div>
          )}
        </Stack>
      </Paper>

      <Paper withBorder radius="md" p="xs" className={classes.reviewActions}>
        <Group
          justify="space-between"
          wrap="wrap"
          gap="xs"
          className={classes.reviewActionGroup}
        >
          <Group gap="xs" className={classes.reviewNavigation}>
            <Button
              className={classes.mobileIconButton}
              variant="default"
              leftSection={<IconArrowLeft size={16} />}
              aria-label="Previous scan card"
              title="Previous scan card"
              disabled={controlsDisabled || selectedIndex === 0}
              onClick={() => onMoveRow(-1)}
            >
              <span className={classes.buttonLabel}>Previous</span>
            </Button>
            <Button
              className={classes.mobileIconButton}
              variant="default"
              rightSection={<IconArrowRight size={16} />}
              aria-label="Next scan card"
              title="Next scan card"
              disabled={controlsDisabled || selectedIndex >= rowCount - 1}
              onClick={() => onMoveRow(1)}
            >
              <span className={classes.buttonLabel}>Next</span>
            </Button>
            <Button
              color="red"
              variant="subtle"
              leftSection={<IconTrash size={16} />}
              disabled={deleteDisabled || controlsDisabled}
              title="Permanently delete this card and remove it from the physical stack"
              onClick={onDelete}
            >
              Delete card
            </Button>
          </Group>
          <Button
            className={classes.reviewConfirm}
            color="teal"
            leftSection={<IconCheck size={17} />}
            disabled={
              controlsDisabled ||
              !selection ||
              !finishAvailable ||
              selection.confirmed
            }
            onClick={onConfirm}
          >
            {selection?.confirmed ? 'Match confirmed' : 'Confirm match'}
          </Button>
        </Group>
        <Text size="xs" c="dimmed" mt="xs" className={classes.deleteGuidance}>
          Deleting {selectedRow.filename} removes it from the scan. Remove the
          physical card from your stack immediately; this cannot be undone.
        </Text>
      </Paper>
    </Stack>
  );
}
