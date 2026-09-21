import { useEffect, useState } from 'react';
import type { RefObject } from 'react';
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
} from '@tabler/icons-react';
import type { ScanRow, ScanSuggestion } from '../api/client';
import type { ScryfallPrinting } from '../api/scryfall-client';
import classes from './ScanReview.module.css';

export interface ReviewSelection {
  printing: ScryfallPrinting;
  confirmed: boolean;
}

interface ScanReviewPanelsProps {
  selectedRow: ScanRow;
  selectedIndex: number;
  rowCount: number;
  suggestion: ScanSuggestion | undefined;
  selection: ReviewSelection | undefined;
  printings: ScryfallPrinting[];
  printingIndex: number;
  loading: boolean;
  error: string | undefined;
  searchRef: RefObject<HTMLInputElement | null>;
  search: string;
  searchSuggestions: string[];
  searching: boolean;
  searchError: string | null;
  searchSelectionLoading: boolean;
  onMoveRow: (change: number) => void;
  onMovePrinting: (change: number) => void;
  onChoosePrinting: (printing: ScryfallPrinting) => void;
  onSearchChange: (value: string) => void;
  onSearchResult: (name: string) => void;
  onConfirm: () => void;
}

type CropRegion = 'bottom-left' | 'mid-right';

const CROP_OFFSETS: Record<CropRegion, { left: string; top: string }> = {
  'bottom-left': { left: '0%', top: '-900%' },
  'mid-right': { left: '-300%', top: '-535%' },
};

function Crop({
  image,
  region,
  label,
}: {
  image: string | null;
  region: CropRegion;
  label: string;
}) {
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    setFailed(false);
  }, [image]);

  if (!image || failed) {
    return (
      <div className={classes.crop}>
        <div className={classes.cropViewport}>
          <div className={classes.imageUnavailable}>Image unavailable</div>
        </div>
        <Text className={classes.cropLabel}>{label}</Text>
      </div>
    );
  }

  return (
    <div className={classes.crop}>
      <div className={classes.cropViewport}>
        <img
          className={classes.cropImage}
          src={image}
          alt=""
          aria-hidden="true"
          style={CROP_OFFSETS[region]}
          onError={() => setFailed(true)}
        />
      </div>
      <Text className={classes.cropLabel}>{label}</Text>
    </div>
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
  printings,
  printingIndex,
  loading,
  error,
  searchRef,
  search,
  searchSuggestions,
  searching,
  searchError,
  searchSelectionLoading,
  onMoveRow,
  onMovePrinting,
  onChoosePrinting,
  onSearchChange,
  onSearchResult,
  onConfirm,
}: ScanReviewPanelsProps) {
  const currentName =
    selection?.printing.name ?? suggestion?.name ?? 'Manual selection required';
  const sourceImage = selectedRow.source_url;
  const candidateImage = selection?.printing.image_url ?? null;
  const needsReview =
    selectedRow.needs_review ||
    selectedRow.error !== null ||
    suggestion === undefined ||
    error !== undefined;

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
              {selectedRow.needs_review ||
              selectedRow.error !== null ||
              error !== undefined
                ? 'This card needs a manual printing choice.'
                : suggestion
                  ? 'Suggested match · advisory only'
                  : 'Choose a card with card search.'}
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
      </Paper>

      <div className={classes.comparison}>
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
              Identified printing
            </Text>
            <Badge color="teal" variant="light">
              Reference
            </Badge>
          </Group>
          {loading ? (
            <Skeleton height={420} radius="sm" />
          ) : (
            <ScanImage
              image={candidateImage}
              alt={`${currentName} reference`}
            />
          )}
        </Paper>
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
            <div className={classes.cropPair}>
              <Crop
                image={sourceImage}
                region="bottom-left"
                label="Your scan · set code"
              />
              <Crop
                image={candidateImage}
                region="bottom-left"
                label={
                  selection
                    ? `Reference · ${selection.printing.set_code.toUpperCase()} ${selection.printing.collector_number}`
                    : 'Reference · set code'
                }
              />
            </div>
            <div className={classes.cropPair}>
              <Crop
                image={sourceImage}
                region="mid-right"
                label="Your scan · set symbol"
              />
              <Crop
                image={candidateImage}
                region="mid-right"
                label="Reference · set symbol"
              />
            </div>
          </div>
        </Paper>
      </div>

      <Paper withBorder radius="md" p="sm">
        <Group justify="space-between" mb="xs" wrap="wrap">
          <Box>
            <Text fw={600} size="sm">
              Other printings
            </Text>
            <Text size="xs" c="dimmed">
              Same card
            </Text>
          </Box>
          <Group gap={3}>
            <ActionIcon
              variant="default"
              aria-label="Previous printing"
              disabled={printingIndex <= 0}
              onClick={() => onMovePrinting(-1)}
            >
              <IconChevronLeft size={16} />
            </ActionIcon>
            <Text size="sm" miw={38} ta="center">
              {printingIndex < 0
                ? '—'
                : `${printingIndex + 1} / ${printings.length}`}
            </Text>
            <ActionIcon
              variant="default"
              aria-label="Next printing"
              disabled={
                printingIndex < 0 || printingIndex >= printings.length - 1
              }
              onClick={() => onMovePrinting(1)}
            >
              <IconChevronRight size={16} />
            </ActionIcon>
          </Group>
        </Group>
        {printings.length > 0 && (
          <div className={classes.printingTabs} aria-label="Printings">
            {printings.map((printing) => (
              <button
                type="button"
                key={printing.id}
                aria-pressed={printing.id === selection?.printing.id}
                className={`${classes.printingButton} ${printing.id === selection?.printing.id ? classes.printingButtonSelected : ''}`}
                onClick={() => onChoosePrinting(printing)}
              >
                {printing.set_code.toUpperCase()}
                <small className={classes.printingButtonMetadata}>
                  #{printing.collector_number}
                </small>
              </button>
            ))}
          </div>
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
          Search for a card, then compare its printings here.
        </Text>
        <Stack gap={4} mt="sm">
          <TextInput
            ref={searchRef}
            aria-label="Search for a card by name"
            value={search}
            onChange={(event) => onSearchChange(event.currentTarget.value)}
            placeholder="Search for a card by name…"
            leftSection={<IconSearch size={16} />}
            disabled={searchSelectionLoading}
          />
          {searching && (
            <Text size="xs" c="dimmed">
              Searching for cards…
            </Text>
          )}
          {searchError && (
            <Text size="xs" c="red.7" role="alert">
              {searchError}
            </Text>
          )}
          {searchSuggestions.slice(0, 6).map((name) => (
            <Button
              key={name}
              variant="subtle"
              justify="flex-start"
              size="compact-sm"
              loading={searchSelectionLoading}
              onClick={() => onSearchResult(name)}
            >
              {name}
            </Button>
          ))}
        </Stack>
      </Paper>

      <Paper withBorder radius="md" p="xs" className={classes.actionBar}>
        <Group justify="space-between" wrap="wrap" gap="xs">
          <Group gap="xs">
            <Button
              variant="default"
              leftSection={<IconArrowLeft size={16} />}
              disabled={selectedIndex === 0}
              onClick={() => onMoveRow(-1)}
            >
              Previous
            </Button>
            <Button
              variant="default"
              rightSection={<IconArrowRight size={16} />}
              disabled={selectedIndex >= rowCount - 1}
              onClick={() => onMoveRow(1)}
            >
              Next
            </Button>
          </Group>
          <Button
            color="teal"
            leftSection={<IconCheck size={17} />}
            disabled={!selection || selection.confirmed}
            onClick={onConfirm}
          >
            {selection?.confirmed ? 'Match confirmed' : 'Confirm match'}
          </Button>
        </Group>
      </Paper>
    </Stack>
  );
}
