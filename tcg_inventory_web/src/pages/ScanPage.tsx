import { useEffect, useRef, useState } from 'react';
import {
  ActionIcon,
  Badge,
  Box,
  Button,
  Group,
  Image,
  Modal,
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
  IconSearch,
  IconUpload,
  IconX,
} from '@tabler/icons-react';
import { AppShellLayout } from '../layouts/AppShellLayout';

type ScanStatus = 'confirmed' | 'current' | 'needs-review';

interface ScannedCard {
  name: string;
  set: string;
  number: string;
  confidence: number;
  status: ScanStatus;
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

const SCANNED_CARDS: ScannedCard[] = [
  {
    name: 'Lightning Bolt',
    set: '2X2',
    number: '117',
    confidence: 98,
    status: 'confirmed',
  },
  {
    name: 'Llanowar Elves',
    set: 'DMU',
    number: '168',
    confidence: 96,
    status: 'confirmed',
  },
  {
    name: 'Opt',
    set: 'M21',
    number: '59',
    confidence: 94,
    status: 'confirmed',
  },
  {
    name: 'Lightning Bolt',
    set: '2X2',
    number: '117',
    confidence: 92,
    status: 'current',
  },
  {
    name: 'Counterspell',
    set: '2X2',
    number: '47',
    confidence: 89,
    status: 'needs-review',
  },
  {
    name: 'Swords to Plowshares',
    set: '2X2',
    number: '34',
    confidence: 87,
    status: 'needs-review',
  },
  {
    name: 'Sol Ring',
    set: 'CMM',
    number: '396',
    confidence: 84,
    status: 'needs-review',
  },
  {
    name: 'Birds of Paradise',
    set: 'M12',
    number: '165',
    confidence: 82,
    status: 'needs-review',
  },
  {
    name: 'Ponder',
    set: 'M10',
    number: '68',
    confidence: 78,
    status: 'needs-review',
  },
];

const SEARCH_RESULTS = [
  'Lightning Bolt — Double Masters 2022',
  'Lightning Bolt — Double Masters 2022',
  'Lava Spike — Champions of Kamigawa',
];

function statusColor(status: ScanStatus) {
  if (status === 'confirmed') return 'teal';
  if (status === 'current') return 'blue';
  return 'orange';
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
  const [reviewing, setReviewing] = useState(true);
  const [cardIndex, setCardIndex] = useState(3);
  const [printingIndex, setPrintingIndex] = useState(0);
  const [confirmed, setConfirmed] = useState(new Set([0, 1, 2]));
  const [searchOpen, setSearchOpen] = useState(false);
  const [search, setSearch] = useState('');
  const searchRef = useRef<HTMLInputElement>(null);
  const scan = SCANNED_CARDS[cardIndex];
  const printing = PRINTINGS[printingIndex];

  const moveCard = (change: number) => {
    setCardIndex((index) =>
      Math.max(0, Math.min(SCANNED_CARDS.length - 1, index + change)),
    );
    setPrintingIndex(0);
  };

  const confirmAndAdvance = () => {
    setConfirmed((previous) => new Set(previous).add(cardIndex));
    if (cardIndex < SCANNED_CARDS.length - 1) moveCard(1);
  };

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement;
      if (target.tagName === 'INPUT') return;
      if (event.key === '/') {
        event.preventDefault();
        setSearchOpen(true);
        return;
      }
      if (event.key === 'j') moveCard(1);
      if (event.key === 'k') moveCard(-1);
      if (event.key === 'h')
        setPrintingIndex((index) => Math.max(0, index - 1));
      if (event.key === 'l')
        setPrintingIndex((index) => Math.min(PRINTINGS.length - 1, index + 1));
      if (event.key === 'c') confirmAndAdvance();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  });

  useEffect(() => {
    if (searchOpen) setTimeout(() => searchRef.current?.focus(), 0);
  }, [searchOpen]);

  if (!reviewing) {
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
                <Button onClick={() => setReviewing(true)}>
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
              {confirmed.size} of 100 confirmed
            </Text>
            <Button variant="default" onClick={() => setReviewing(false)}>
              New scan
            </Button>
            <Button disabled={confirmed.size < 100}>Create import</Button>
          </Group>
        </Group>
        <Progress value={confirmed.size} size="sm" color="teal" />

        <div className="scan-review-layout">
          <Paper withBorder radius="md" className="scan-queue">
            <div className="scan-queue-header">
              <Text fw={600} size="sm">
                Cards
              </Text>
              <Text c="dimmed" size="xs">
                j / k
              </Text>
            </div>
            <div className="scan-queue-list">
              {SCANNED_CARDS.map((card, index) => (
                <button
                  key={`${card.name}-${index}`}
                  type="button"
                  className={`scan-queue-card ${index === cardIndex ? 'is-selected' : ''}`}
                  onClick={() => {
                    setCardIndex(index);
                    setPrintingIndex(0);
                  }}
                >
                  <span
                    className={`scan-status-dot ${confirmed.has(index) ? 'is-confirmed' : statusColor(card.status)}`}
                  />
                  <span>
                    <strong>{index + 1}</strong> {card.name}
                    <small>
                      {card.set} · {card.number}
                    </small>
                  </span>
                  {!confirmed.has(index) && (
                    <span className="scan-confidence">{card.confidence}%</span>
                  )}
                </button>
              ))}
              <Text px="sm" pt="xs" size="xs" c="dimmed">
                91 more cards
              </Text>
            </div>
          </Paper>

          <Stack gap="sm" className="scan-reviewer">
            <Paper withBorder radius="md" p="md">
              <Group justify="space-between" wrap="nowrap">
                <div>
                  <Text size="xs" tt="uppercase" fw={700} c="dimmed">
                    Card {cardIndex + 1} of 100
                  </Text>
                  <Title order={3}>{printing.name}</Title>
                  <Text size="sm" c="dimmed">
                    CollectorVision match · {scan.confidence}% confidence
                  </Text>
                </div>
                <Badge
                  color={scan.confidence < 85 ? 'orange' : 'blue'}
                  variant="light"
                >
                  {scan.confidence < 85 ? 'check carefully' : 'high confidence'}
                </Badge>
              </Group>
            </Paper>

            <div className="scan-compare">
              <Paper withBorder radius="md" p="sm" className="scan-card-panel">
                <Text fw={600} size="sm" mb="xs">
                  Your scan
                </Text>
                <Image
                  src={printing.image}
                  alt="Scanned Lightning Bolt"
                  className="scan-card-image scan-photo-treatment"
                />
                <div className="scan-crops">
                  <Crop
                    image={printing.image}
                    position="left bottom"
                    label="Set code + number"
                  />
                  <Crop
                    image={printing.image}
                    position="right center"
                    label="Set symbol"
                  />
                </div>
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
                <div className="scan-crops">
                  <Crop
                    image={printing.image}
                    position="left bottom"
                    label="{printing.code} · {printing.number}"
                  />
                  <Crop
                    image={printing.image}
                    position="right center"
                    label="Set symbol"
                  />
                </div>
              </Paper>
            </div>

            <Paper withBorder radius="md" p="sm">
              <Group justify="space-between" mb="xs">
                <div>
                  <Text fw={600} size="sm">
                    Other printings
                  </Text>
                  <Text size="xs" c="dimmed">
                    Same card · use h / l to compare
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
                    {printingIndex + 1} / {PRINTINGS.length}
                  </Text>
                  <ActionIcon
                    variant="default"
                    aria-label="Next printing"
                    onClick={() =>
                      setPrintingIndex((index) =>
                        Math.min(PRINTINGS.length - 1, index + 1),
                      )
                    }
                  >
                    <IconChevronRight size={16} />
                  </ActionIcon>
                </Group>
              </Group>
              <Group gap="xs" wrap="nowrap" className="scan-printing-tabs">
                {PRINTINGS.map((option, index) => (
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
                <Button
                  variant="subtle"
                  size="compact-sm"
                  leftSection={<IconSearch size={14} />}
                  onClick={() => setSearchOpen(true)}
                >
                  Find another
                </Button>
              </Group>
            </Paper>

            <Group justify="space-between" className="scan-actions">
              <Button
                variant="default"
                leftSection={<IconArrowLeft size={16} />}
                onClick={() => moveCard(-1)}
              >
                Previous <kbd>k</kbd>
              </Button>
              <Button
                color="teal"
                size="md"
                leftSection={<IconCheck size={17} />}
                onClick={confirmAndAdvance}
              >
                Confirm match <kbd>c</kbd>
              </Button>
              <Button
                variant="default"
                rightSection={<IconArrowRight size={16} />}
                onClick={() => moveCard(1)}
              >
                Next <kbd>j</kbd>
              </Button>
            </Group>
          </Stack>
        </div>
        <Text size="xs" c="dimmed">
          Keyboard: <kbd>j</kbd>/<kbd>k</kbd> card · <kbd>h</kbd>/<kbd>l</kbd>{' '}
          printing · <kbd>c</kbd> confirm + next · <kbd>/</kbd> find card
        </Text>
      </Stack>

      <Modal
        opened={searchOpen}
        onClose={() => setSearchOpen(false)}
        title="Find the correct card"
        centered
      >
        <Stack>
          <TextInput
            ref={searchRef}
            value={search}
            onChange={(event) => setSearch(event.currentTarget.value)}
            placeholder="Search Scryfall by card name…"
            leftSection={<IconSearch size={16} />}
          />
          <Text size="xs" c="dimmed">
            Search results update the identified card, then you can select its
            printing.
          </Text>
          {SEARCH_RESULTS.filter((result) =>
            result.toLowerCase().includes(search.toLowerCase()),
          ).map((result) => (
            <Button
              key={result}
              variant="default"
              justify="flex-start"
              onClick={() => {
                setPrintingIndex(result.includes('Double') ? 2 : 0);
                setSearchOpen(false);
              }}
            >
              {result}
            </Button>
          ))}
          <Button
            variant="subtle"
            color="red"
            leftSection={<IconX size={16} />}
          >
            Mark as unidentified
          </Button>
        </Stack>
      </Modal>
    </AppShellLayout>
  );
}
