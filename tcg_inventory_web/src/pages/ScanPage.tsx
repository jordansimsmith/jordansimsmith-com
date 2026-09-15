import { useEffect, useRef, useState } from 'react';
import {
  ActionIcon,
  Badge,
  Box,
  Button,
  Group,
  Image,
  Paper,
  Progress,
  Select,
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
  IconPlus,
  IconSearch,
  IconTrash,
  IconUpload,
} from '@tabler/icons-react';
import { AppShellLayout } from '../layouts/AppShellLayout';

interface ScannedCard {
  name: string;
  set: string;
  number: string;
  confidence: number;
}

interface Printing {
  name: string;
  set: string;
  code: string;
  number: string;
  image: string;
}

interface ScryfallCard {
  id: string;
  name: string;
  set_name: string;
  set: string;
  collector_number: string;
  image_uris?: { normal: string };
  prints_search_uri: string;
}

const PRINTINGS = [
  {
    name: 'Lightning Bolt',
    set: 'Double Masters 2022',
    code: '2X2',
    number: '117',
    image:
      'https://api.scryfall.com/cards/f29ba16f-c8fb-42fe-aabf-87089cb214a7?format=image&version=normal',
  },
  {
    name: 'Lightning Bolt',
    set: 'The List',
    code: 'PLST',
    number: 'LLE-167',
    image:
      'https://api.scryfall.com/cards/132dc07f-74e2-4bd7-bdb1-4f5d5253c7f2?format=image&version=normal',
  },
  {
    name: 'Lightning Bolt',
    set: 'Magic 2011',
    code: 'M11',
    number: '149',
    image:
      'https://api.scryfall.com/cards/e768c957-3a1f-42f5-853a-96942f645df5?format=image&version=normal',
  },
];

const MATCHES_BY_CARD: Record<string, Printing[]> = {
  'Lightning Bolt': PRINTINGS,
  'Llanowar Elves': [
    {
      name: 'Llanowar Elves',
      set: 'Dominaria United',
      code: 'DMU',
      number: '168',
      image:
        'https://api.scryfall.com/cards/6a0b230b-d391-4998-a3f7-7b158a0ec2cd?format=image&version=normal',
    },
  ],
  Opt: [
    {
      name: 'Opt',
      set: 'Core Set 2021',
      code: 'M21',
      number: '59',
      image:
        'https://api.scryfall.com/cards/323db259-d35e-467d-9a46-4adcb2fc107c?format=image&version=normal',
    },
  ],
  Counterspell: [
    {
      name: 'Counterspell',
      set: 'Magic: The Gathering Foundations',
      code: 'FDN',
      number: '153',
      image:
        'https://api.scryfall.com/cards/4f616706-ec97-4923-bb1e-11a69fbaa1f8?format=image&version=normal',
    },
  ],
  'Swords to Plowshares': [
    {
      name: 'Swords to Plowshares',
      set: 'Mystery Booster 2',
      code: 'MB2',
      number: '257',
      image:
        'https://api.scryfall.com/cards/b4e9c870-23c0-413a-ae39-265f09da16d1?format=image&version=normal',
    },
  ],
  'Sol Ring': [
    {
      name: 'Sol Ring',
      set: 'Commander Masters',
      code: 'CMM',
      number: '396',
      image:
        'https://api.scryfall.com/cards/46ca0b66-a000-4483-b916-f5b89e710244?format=image&version=normal',
    },
  ],
  'Birds of Paradise': [
    {
      name: 'Birds of Paradise',
      set: 'Magic 2012',
      code: 'M12',
      number: '165',
      image:
        'https://api.scryfall.com/cards/307d4236-1e54-43e3-83f1-063d49d16dda?format=image&version=normal',
    },
  ],
  Ponder: [
    {
      name: 'Ponder',
      set: 'Magic 2010',
      code: 'M10',
      number: '68',
      image:
        'https://api.scryfall.com/cards/3c02b8ee-84cb-44cb-ba14-9e725a9d03ee?format=image&version=normal',
    },
  ],
};

const SCANNED_CARDS: ScannedCard[] = [
  {
    name: 'Lightning Bolt',
    set: '2X2',
    number: '117',
    confidence: 98,
  },
  {
    name: 'Llanowar Elves',
    set: 'DMU',
    number: '168',
    confidence: 96,
  },
  {
    name: 'Opt',
    set: 'M21',
    number: '59',
    confidence: 94,
  },
  {
    name: 'Lightning Bolt',
    set: '2X2',
    number: '117',
    confidence: 92,
  },
  {
    name: 'Counterspell',
    set: '2X2',
    number: '47',
    confidence: 89,
  },
  {
    name: 'Swords to Plowshares',
    set: '2X2',
    number: '34',
    confidence: 87,
  },
  {
    name: 'Sol Ring',
    set: 'CMM',
    number: '396',
    confidence: 84,
  },
  {
    name: 'Birds of Paradise',
    set: 'M12',
    number: '165',
    confidence: 82,
  },
  {
    name: 'Ponder',
    set: 'M10',
    number: '68',
    confidence: 78,
  },
];

const SCAN_JOBS = [
  {
    id: 'scan-2026-09-16-01',
    label: 'September 16 · ADF batch',
    detail: '100 cards · Near mint · Non-foil',
    status: 'Reviewing',
    confirmed: '3 / 100 confirmed',
  },
  {
    id: 'scan-2026-09-14-01',
    label: 'September 14 · Binder pages',
    detail: '48 cards · Lightly played · Foil',
    status: 'Ready to import',
    confirmed: '48 / 48 confirmed',
  },
  {
    id: 'scan-2026-09-09-01',
    label: 'September 9 · ADF batch',
    detail: '96 cards · Near mint · Non-foil',
    status: 'Imported',
    confirmed: '96 / 96 confirmed',
  },
];

function toPrinting(card: ScryfallCard): Printing {
  if (!card.image_uris?.normal) {
    throw new Error(`${card.name} does not have a single-faced card image`);
  }
  return {
    name: card.name,
    set: card.set_name,
    code: card.set.toUpperCase(),
    number: card.collector_number,
    image: card.image_uris.normal,
  };
}

function Crop({
  image,
  position,
  label,
}: {
  image: string;
  position: string;
  label: string;
}) {
  return (
    <div className="scan-crop">
      <div
        className="scan-crop-image"
        style={{
          backgroundImage: `url(${image})`,
          backgroundPosition: position,
        }}
      />
      <Text className="scan-crop-label">{label}</Text>
    </div>
  );
}

export function ScanPage() {
  const [view, setView] = useState<'jobs' | 'new' | 'review'>('jobs');
  const [cardIndex, setCardIndex] = useState(3);
  const [printingIndex, setPrintingIndex] = useState(0);
  const [confirmed, setConfirmed] = useState(new Set<number>());
  const [deleted, setDeleted] = useState(new Set<number>());
  const [search, setSearch] = useState('');
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [manualPrintings, setManualPrintings] = useState(
    new Map<number, Printing[]>(),
  );
  const searchRef = useRef<HTMLInputElement>(null);
  const cardRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const visibleCardIndexes = SCANNED_CARDS.map((_, index) => index).filter(
    (index) => !deleted.has(index),
  );
  const sourceCardIndex = visibleCardIndexes[cardIndex];
  const scan = SCANNED_CARDS[sourceCardIndex];
  const scanImage = MATCHES_BY_CARD[scan.name][0].image;
  const printings =
    manualPrintings.get(sourceCardIndex) ?? MATCHES_BY_CARD[scan.name];
  const printing = printings[printingIndex];

  const moveCard = (change: number) => {
    setCardIndex((index) =>
      Math.max(0, Math.min(visibleCardIndexes.length - 1, index + change)),
    );
    setPrintingIndex(0);
  };

  const confirmAndAdvance = () => {
    if (confirmed.has(sourceCardIndex)) return;
    setConfirmed((previous) => new Set(previous).add(sourceCardIndex));
    if (cardIndex < visibleCardIndexes.length - 1) moveCard(1);
  };

  const deleteCurrentCard = () => {
    setDeleted((previous) => new Set(previous).add(sourceCardIndex));
    setConfirmed((previous) => {
      const next = new Set(previous);
      next.delete(sourceCardIndex);
      return next;
    });
    setManualPrintings((previous) => {
      const next = new Map(previous);
      next.delete(sourceCardIndex);
      return next;
    });
    if (visibleCardIndexes.length === 1) {
      setView('jobs');
    } else {
      setCardIndex((index) => Math.min(index, visibleCardIndexes.length - 2));
      setPrintingIndex(0);
    }
  };

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement;
      if (target.tagName === 'INPUT') return;
      if (event.key === '/') {
        event.preventDefault();
        searchRef.current?.focus();
        return;
      }
      if (event.key === 'j') {
        event.preventDefault();
        moveCard(1);
      }
      if (event.key === 'k') {
        event.preventDefault();
        moveCard(-1);
      }
      if (event.key === 'h') {
        event.preventDefault();
        setPrintingIndex((index) => Math.max(0, index - 1));
      }
      if (event.key === 'l') {
        event.preventDefault();
        setPrintingIndex((index) => Math.min(printings.length - 1, index + 1));
      }
      if (event.key === 'c') {
        event.preventDefault();
        confirmAndAdvance();
      }
      if (event.key === 'd') {
        event.preventDefault();
        deleteCurrentCard();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  });

  useEffect(() => {
    if (search.trim().length < 2) {
      setSuggestions([]);
      setSearchError(null);
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(async () => {
      setSearching(true);
      setSearchError(null);
      try {
        const response = await fetch(
          `https://api.scryfall.com/cards/autocomplete?q=${encodeURIComponent(search)}`,
          { signal: controller.signal },
        );
        if (!response.ok) throw new Error('Scryfall search is unavailable');
        const data = (await response.json()) as { data: string[] };
        if (!controller.signal.aborted) setSuggestions(data.data);
      } catch (error) {
        if (!controller.signal.aborted) {
          setSearchError(
            error instanceof Error ? error.message : 'Scryfall search failed',
          );
        }
      } finally {
        if (!controller.signal.aborted) setSearching(false);
      }
    }, 250);
    return () => {
      controller.abort();
      window.clearTimeout(timer);
    };
  }, [search]);

  useEffect(() => {
    cardRefs.current[cardIndex]?.scrollIntoView({ block: 'nearest' });
  }, [cardIndex]);

  const selectScryfallCard = async (name: string) => {
    setSearching(true);
    setSearchError(null);
    try {
      const selectedResponse = await fetch(
        `https://api.scryfall.com/cards/named?exact=${encodeURIComponent(name)}`,
      );
      if (!selectedResponse.ok)
        throw new Error('Scryfall could not find that card');
      const selected = (await selectedResponse.json()) as ScryfallCard;
      const printingsResponse = await fetch(selected.prints_search_uri);
      if (!printingsResponse.ok)
        throw new Error('Scryfall could not load printings');
      const printingsData = (await printingsResponse.json()) as {
        data: ScryfallCard[];
      };
      const allPrintings = [
        selected,
        ...printingsData.data.filter((card) => card.id !== selected.id),
      ].map(toPrinting);
      setManualPrintings((previous) => {
        const next = new Map(previous);
        next.set(sourceCardIndex, allPrintings);
        return next;
      });
      setPrintingIndex(0);
      setSearch('');
    } catch (error) {
      setSearchError(
        error instanceof Error ? error.message : 'Scryfall search failed',
      );
    } finally {
      setSearching(false);
    }
  };

  if (view === 'jobs') {
    return (
      <AppShellLayout>
        <Stack gap="lg" maw={1000}>
          <Group justify="space-between">
            <div>
              <Title order={2}>Scans</Title>
              <Text c="dimmed">
                Upload a batch, verify every card, then create an import.
              </Text>
            </div>
            <Button
              leftSection={<IconPlus size={17} />}
              onClick={() => setView('new')}
            >
              New scan
            </Button>
          </Group>
          <Paper withBorder radius="md" className="scan-jobs">
            {SCAN_JOBS.map((job) => (
              <button
                key={job.id}
                type="button"
                className="scan-job"
                onClick={() => setView('review')}
              >
                <span>
                  <Text fw={600}>{job.label}</Text>
                  <Text size="sm" c="dimmed">
                    {job.detail}
                  </Text>
                </span>
                <span className="scan-job-state">
                  <Badge
                    color={
                      job.status === 'Imported'
                        ? 'gray'
                        : job.status === 'Reviewing'
                          ? 'blue'
                          : 'teal'
                    }
                    variant="light"
                  >
                    {job.status}
                  </Badge>
                  <Text size="sm" c="dimmed">
                    {job.confirmed}
                  </Text>
                </span>
              </button>
            ))}
          </Paper>
        </Stack>
      </AppShellLayout>
    );
  }

  if (view === 'new') {
    return (
      <AppShellLayout>
        <Stack gap="xl" maw={900}>
          <div>
            <Title order={2}>New scan</Title>
            <Text c="dimmed">
              Scan one condition and finish at a time. Those defaults apply to
              every confirmed card.
            </Text>
          </div>
          <Paper withBorder p="xl" radius="md">
            <Stack gap="lg">
              <Group grow align="end">
                <Select
                  label="Condition"
                  defaultValue="NM"
                  data={[
                    { value: 'NM', label: 'Near mint' },
                    { value: 'LP', label: 'Lightly played' },
                    { value: 'MP', label: 'Moderately played' },
                  ]}
                />
                <Select
                  label="Finish"
                  defaultValue="nonfoil"
                  data={[
                    { value: 'nonfoil', label: 'Non-foil' },
                    { value: 'foil', label: 'Foil' },
                    { value: 'etched', label: 'Etched' },
                  ]}
                />
              </Group>
              <Box className="scan-dropzone">
                <IconUpload size={30} stroke={1.5} />
                <div>
                  <Text fw={600}>Drop a folder of card scans here</Text>
                  <Text size="sm" c="dimmed">
                    JPEG, PNG, or TIFF · one image per card
                  </Text>
                </div>
                <Button variant="default">Choose folder</Button>
              </Box>
              <Group justify="space-between">
                <Group gap="xs">
                  <IconPhoto size={17} />
                  <Text size="sm">100 scans ready</Text>
                </Group>
                <Button onClick={() => setView('review')}>
                  Identify 100 cards
                </Button>
              </Group>
            </Stack>
          </Paper>
          <Paper withBorder p="md" bg="gray.0">
            <Text size="sm" fw={600}>
              What happens next
            </Text>
            <Text size="sm" c="dimmed">
              Images upload, identification runs in the background, then you
              review each proposed Scryfall printing before creating an import.
            </Text>
          </Paper>
        </Stack>
      </AppShellLayout>
    );
  }

  return (
    <AppShellLayout>
      <Stack gap="md" className="scan-page">
        <Group justify="space-between" align="end">
          <div>
            <Group gap="xs">
              <Title order={2}>Review scan</Title>
              <Badge color="teal" variant="light">
                ready
              </Badge>
            </Group>
            <Text c="dimmed" size="sm">
              ADF intake · 100 cards · Near mint · Non-foil
            </Text>
          </div>
          <Group gap="sm">
            <Text size="sm" c="dimmed">
              {confirmed.size} of {visibleCardIndexes.length} confirmed
            </Text>
            <Button disabled={confirmed.size < visibleCardIndexes.length}>
              Create import
            </Button>
          </Group>
        </Group>
        <Progress
          value={(confirmed.size / visibleCardIndexes.length) * 100}
          size="sm"
          color="teal"
        />

        <div className="scan-review-layout">
          <Paper withBorder radius="md" className="scan-queue">
            <div className="scan-queue-header">
              <Text fw={600} size="sm">
                Cards
              </Text>
            </div>
            <div className="scan-queue-list">
              {visibleCardIndexes.map((sourceIndex, index) => {
                const card = SCANNED_CARDS[sourceIndex];
                return (
                  <button
                    key={`${card.name}-${index}`}
                    type="button"
                    ref={(element) => {
                      cardRefs.current[index] = element;
                    }}
                    aria-current={index === cardIndex ? 'true' : undefined}
                    className={`scan-queue-card ${index === cardIndex ? 'is-selected' : ''}`}
                    onClick={() => {
                      setCardIndex(index);
                      setPrintingIndex(0);
                    }}
                  >
                    <span
                      className={`scan-status-dot ${confirmed.has(sourceIndex) ? 'is-confirmed' : card.confidence < 90 ? 'orange' : 'gray'}`}
                    />
                    <span>
                      <strong>{index + 1}</strong> {card.name}
                      <small>
                        {card.set} · {card.number}
                      </small>
                    </span>
                    {!confirmed.has(sourceIndex) && (
                      <span className="scan-confidence">
                        {card.confidence}%
                      </span>
                    )}
                  </button>
                );
              })}
              <Text px="sm" pt="xs" size="xs" c="dimmed">
                {Math.max(0, visibleCardIndexes.length - 9)} more cards
              </Text>
            </div>
          </Paper>

          <Stack gap="sm" className="scan-reviewer">
            <Paper withBorder radius="md" p="md">
              <Group justify="space-between" wrap="nowrap">
                <div>
                  <Text size="xs" tt="uppercase" fw={700} c="dimmed">
                    Card {cardIndex + 1} of {visibleCardIndexes.length}
                  </Text>
                  <Title order={3}>{printing.name}</Title>
                  <Text size="sm" c="dimmed">
                    CollectorVision match · {scan.confidence}% confidence
                  </Text>
                </div>
                <Badge
                  color={scan.confidence < 90 ? 'orange' : 'gray'}
                  variant="light"
                >
                  {scan.confidence < 90 ? 'check carefully' : 'high confidence'}
                </Badge>
              </Group>
            </Paper>

            <div className="scan-compare">
              <Paper withBorder radius="md" p="sm" className="scan-card-panel">
                <Text fw={600} size="sm" mb="xs">
                  Your scan
                </Text>
                <Image
                  src={scanImage}
                  alt={`Scanned ${scan.name}`}
                  className="scan-card-image scan-photo-treatment"
                />
              </Paper>
              <Paper
                withBorder
                radius="md"
                p="sm"
                className="scan-card-panel scan-match-panel"
              >
                <Group justify="space-between" mb="xs">
                  <Text fw={600} size="sm">
                    Identified printing
                  </Text>
                  <Badge color="teal" variant="light">
                    Scryfall
                  </Badge>
                </Group>
                <Image
                  src={printing.image}
                  alt={`${printing.name}, ${printing.set}`}
                  className="scan-card-image"
                />
              </Paper>
              <Paper
                withBorder
                radius="md"
                p="sm"
                className="scan-verification-panel"
              >
                <Text fw={600} size="sm" mb="xs">
                  Print details
                </Text>
                <Stack gap="sm">
                  <div className="scan-crop-pair">
                    <Crop
                      image={scanImage}
                      position="left bottom"
                      label="Your scan · set code"
                    />
                    <Crop
                      image={printing.image}
                      position="left bottom"
                      label={`Scryfall · ${printing.code} ${printing.number}`}
                    />
                  </div>
                  <div className="scan-crop-pair">
                    <Crop
                      image={scanImage}
                      position="right center"
                      label="Your scan · set symbol"
                    />
                    <Crop
                      image={printing.image}
                      position="right center"
                      label="Scryfall · set symbol"
                    />
                  </div>
                </Stack>
              </Paper>
            </div>

            <Paper withBorder radius="md" p="sm">
              <Group justify="space-between" mb="xs">
                <div>
                  <Text fw={600} size="sm">
                    Other printings
                  </Text>
                  <Text size="xs" c="dimmed">
                    Same card
                  </Text>
                </div>
                <Group gap={3}>
                  <ActionIcon
                    variant="default"
                    aria-label="Previous printing"
                    onClick={() =>
                      setPrintingIndex((index) => Math.max(0, index - 1))
                    }
                  >
                    <IconChevronLeft size={16} />
                  </ActionIcon>
                  <Text size="sm" miw={38} ta="center">
                    {printingIndex + 1} / {printings.length}
                  </Text>
                  <ActionIcon
                    variant="default"
                    aria-label="Next printing"
                    onClick={() =>
                      setPrintingIndex((index) =>
                        Math.min(printings.length - 1, index + 1),
                      )
                    }
                  >
                    <IconChevronRight size={16} />
                  </ActionIcon>
                </Group>
              </Group>
              <Group gap="xs" wrap="nowrap" className="scan-printing-tabs">
                {printings.map((option, index) => (
                  <button
                    type="button"
                    key={option.code}
                    className={index === printingIndex ? 'is-active' : ''}
                    onClick={() => setPrintingIndex(index)}
                  >
                    {option.code}
                    <small>#{option.number}</small>
                  </button>
                ))}
              </Group>
            </Paper>

            <Paper withBorder radius="md" p="sm">
              <div>
                <div>
                  <Text fw={600} size="sm">
                    Wrong card?
                  </Text>
                  <Text size="xs" c="dimmed">
                    Search Scryfall, then compare that card's printings here.
                  </Text>
                </div>
              </div>
              <Stack gap={4} mt="sm">
                <TextInput
                  ref={searchRef}
                  value={search}
                  onChange={(event) => setSearch(event.currentTarget.value)}
                  placeholder="Search Scryfall by card name…"
                  leftSection={<IconSearch size={16} />}
                />
                {searching && (
                  <Text size="xs" c="dimmed">
                    Searching Scryfall…
                  </Text>
                )}
                {searchError && (
                  <Text size="xs" c="red">
                    {searchError}
                  </Text>
                )}
                {suggestions.slice(0, 6).map((suggestion) => (
                  <Button
                    key={suggestion}
                    variant="subtle"
                    justify="flex-start"
                    size="compact-sm"
                    onClick={() => void selectScryfallCard(suggestion)}
                  >
                    {suggestion}
                  </Button>
                ))}
              </Stack>
            </Paper>

            <Paper withBorder radius="md" p="xs">
              <Group justify="space-between">
                <Group gap="xs">
                  <Button
                    variant="default"
                    leftSection={<IconArrowLeft size={16} />}
                    onClick={() => moveCard(-1)}
                  >
                    Previous
                  </Button>
                  <Button
                    variant="default"
                    rightSection={<IconArrowRight size={16} />}
                    onClick={() => moveCard(1)}
                  >
                    Next
                  </Button>
                </Group>
                <Group gap="xs">
                  <Button
                    variant="subtle"
                    color="red"
                    leftSection={<IconTrash size={16} />}
                    onClick={deleteCurrentCard}
                  >
                    Delete
                  </Button>
                  <Button
                    color="teal"
                    size="md"
                    leftSection={<IconCheck size={17} />}
                    onClick={confirmAndAdvance}
                    disabled={confirmed.has(sourceCardIndex)}
                  >
                    Confirm match
                  </Button>
                </Group>
              </Group>
            </Paper>
          </Stack>
        </div>
      </Stack>
    </AppShellLayout>
  );
}
