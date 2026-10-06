import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { GamesProvider } from '../GamesProvider';
import { apiClient } from '../api/client';
import type {
  CatalogCard,
  Game,
  ScanConfirmationRow,
  ScanDetail,
  ScanRow,
} from '../api/client';
import { ScanReview } from './ScanReview';

const REVIEW_REGIONS = [
  {
    id: 'set_code',
    display_name: 'Set code',
    x: 0,
    y: 0.9,
    width: 0.25,
    height: 0.1,
  },
  {
    id: 'set_symbol',
    display_name: 'Set symbol',
    x: 0.75,
    y: 0.535,
    width: 0.25,
    height: 0.1,
  },
];

const REGISTERED_GAMES: Game[] = [
  {
    id: 'mtg',
    display_name: 'Magic: The Gathering',
    scanning_enabled: true,
    csv_import_enabled: true,
    scan_review_image_regions: REVIEW_REGIONS,
    finishes: [
      { id: 'normal', display_name: 'Normal' },
      { id: 'foil', display_name: 'Foil' },
      { id: 'etched', display_name: 'Etched' },
    ],
  },
];

function card(
  externalId: string,
  overrides: Partial<CatalogCard> = {},
): CatalogCard {
  return {
    game: 'mtg',
    external_id: externalId,
    name: 'Lightning Bolt',
    set_code: '2x2',
    set_name: 'Double Masters 2022',
    collector_number: '117',
    image_urls: {
      small: 'https://image.test/small.jpg',
      normal: 'https://image.test/normal.jpg',
    },
    available_finishes: ['normal', 'foil'],
    ...overrides,
  };
}

const firstCard = card('product-1');
const secondCard = card('product-2', {
  set_code: 'm11',
  set_name: 'Magic 2011',
  collector_number: '149',
  image_urls: { small: null, normal: 'https://image.test/second.jpg' },
});
const thirdCard = card('product-3', {
  set_code: 'sta',
  set_name: 'Strixhaven Mystical Archive',
  collector_number: '42',
});

function row(overrides: Partial<ScanRow> = {}): ScanRow {
  return {
    scan_position: 1,
    filename: '001.jpg',
    size_bytes: 4,
    uploaded: true,
    upload_url: null,
    upload_headers: null,
    status: 'suggested',
    needs_review: false,
    suggestions: [
      {
        external_id: firstCard.external_id,
        name: firstCard.name,
        score: 0.98,
      },
    ],
    source_url: 'data:image/svg+xml,source',
    error: null,
    ...overrides,
  };
}

function scan(rows: ScanRow[]): ScanDetail {
  return {
    scan_id: 'scan-reviewing',
    game: 'mtg',
    status: 'reviewing',
    condition: 'NM',
    finish: 'normal',
    row_count: rows.length,
    error: null,
    import_id: null,
    created_at: 1765420932,
    rows,
  };
}

function renderReview(
  detail: ScanDetail,
  options: {
    onDeleteRow?: (scanPosition: number) => Promise<void>;
    onConfirmScan?: (rows: ScanConfirmationRow[]) => Promise<void>;
    games?: Game[];
  } = {},
) {
  const onDeleteRow =
    options.onDeleteRow ?? vi.fn().mockResolvedValue(undefined);
  const onConfirmScan =
    options.onConfirmScan ?? vi.fn().mockResolvedValue(undefined);
  const rendered = render(
    <MantineProvider>
      <GamesProvider initialGames={options.games ?? REGISTERED_GAMES}>
        <ScanReview
          scan={detail}
          confirmationPending={false}
          onDeleteRow={onDeleteRow}
          onConfirmScan={onConfirmScan}
        />
      </GamesProvider>
    </MantineProvider>,
  );
  return { ...rendered, onDeleteRow, onConfirmScan };
}

function mockSuggestionLookup(
  selectedCard = firstCard,
  alternatives: CatalogCard[] = [firstCard, secondCard],
  nextContinuation: string | null = null,
) {
  const detail = vi
    .spyOn(apiClient, 'getCatalogCard')
    .mockResolvedValue(selectedCard);
  const findAlternatives = vi
    .spyOn(apiClient, 'findCatalogAlternatives')
    .mockResolvedValue({
      cards: alternatives,
      next_continuation: nextContinuation,
    });
  vi.spyOn(apiClient, 'findCatalogCards').mockResolvedValue({
    cards: [],
    next_continuation: null,
  });
  return { detail, findAlternatives };
}

describe('ScanReview', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('loads the exact suggestion and displays catalog metadata and configured regions', async () => {
    const { detail, findAlternatives } = mockSuggestionLookup();

    renderReview(scan([row()]));

    expect(
      await screen.findByRole('heading', { name: 'Lightning Bolt' }),
    ).toBeDefined();
    expect(screen.getAllByText('Double Masters 2022 · 2X2 #117')).toHaveLength(
      2,
    );
    expect(screen.getByText('Normal available')).toBeDefined();
    expect(screen.getByAltText('Scanned 001.jpg')).toHaveProperty(
      'src',
      'data:image/svg+xml,source',
    );
    expect(screen.getByAltText('Lightning Bolt reference')).toHaveProperty(
      'src',
      'https://image.test/normal.jpg',
    );
    expect(screen.getByText('Your scan · Set code')).toBeDefined();
    expect(screen.getByText('Reference · Set symbol')).toBeDefined();
    expect(detail).toHaveBeenCalledWith('mtg', firstCard.external_id);
    expect(findAlternatives).toHaveBeenCalledWith({
      game: 'mtg',
      external_id: firstCard.external_id,
      finish: 'normal',
    });
    expect(screen.queryByText(/scryfall/i)).toBeNull();
  });

  it('keeps an exact suggestion selected when the scan finish is unavailable', async () => {
    const user = userEvent.setup();
    const ineligibleCard = card('product-1', { available_finishes: ['foil'] });
    mockSuggestionLookup(ineligibleCard, [secondCard]);

    renderReview(scan([row()]));

    expect(await screen.findByText('Normal unavailable')).toBeDefined();
    expect(screen.getAllByText('Double Masters 2022 · 2X2 #117')).toHaveLength(
      2,
    );
    expect(
      screen.getByRole('button', { name: 'Confirm match' }),
    ).toHaveProperty('disabled', true);

    await user.click(screen.getByRole('button', { name: /M11.*#149/ }));

    expect(screen.getByText('Normal available')).toBeDefined();
    expect(
      screen.getByRole('button', { name: 'Confirm match' }),
    ).toHaveProperty('disabled', false);
  });

  it('searches and selects a product for a row without a recognition suggestion', async () => {
    const user = userEvent.setup();
    const counterspell = card('counterspell-id', {
      name: 'Counterspell',
      set_code: 'fdn',
      set_name: 'Foundations',
      collector_number: '153',
    });
    const alternateCounterspell = card('counterspell-alternate-id', {
      name: 'Counterspell',
      set_code: 'm10',
      set_name: 'Magic 2010',
      collector_number: '54',
    });
    const search = vi.spyOn(apiClient, 'findCatalogCards').mockResolvedValue({
      cards: [counterspell, alternateCounterspell],
      next_continuation: null,
    });
    vi.spyOn(apiClient, 'findCatalogAlternatives').mockResolvedValue({
      cards: [counterspell],
      next_continuation: null,
    });
    const noSuggestion = row({
      suggestions: [],
      needs_review: true,
      status: 'needs_review',
      error: 'recognition was inconclusive',
    });

    renderReview(scan([noSuggestion]));

    expect(
      await screen.findByRole('heading', { name: 'Manual selection required' }),
    ).toBeDefined();
    const searchInput = screen.getByRole('textbox', {
      name: 'Search catalog cards',
    });
    await user.type(searchInput, 'Counterspell');
    await waitFor(() =>
      expect(search).toHaveBeenCalledWith({
        game: 'mtg',
        query: 'Counterspell',
        finish: 'normal',
      }),
    );
    const searchResults = screen.getByLabelText('Catalog results');
    const searchResult = within(searchResults).getByRole('button', {
      name: /Counterspell.*FDN #153/,
    });
    expect(within(searchResult).getByText('Normal eligible')).toBeDefined();
    expect(searchResult.querySelector('img')?.getAttribute('src')).toBe(
      'https://image.test/small.jpg',
    );
    expect(
      within(searchResults).getAllByRole('button', { name: /Counterspell/ }),
    ).toHaveLength(2);
    await user.keyboard('{Enter}');

    expect(
      await screen.findByRole('heading', { name: 'Counterspell' }),
    ).toBeDefined();
    expect(screen.getAllByText('Foundations · FDN #153')).toHaveLength(2);
    expect(screen.queryByText('Magic 2010 · M10 #54')).toBeNull();
    expect(
      screen.getByRole('button', { name: 'Confirm match' }),
    ).toHaveProperty('disabled', false);
  });

  it('clears row confirmation when the selected product changes', async () => {
    const user = userEvent.setup();
    mockSuggestionLookup();
    renderReview(scan([row()]));

    const confirm = await screen.findByRole('button', {
      name: 'Confirm match',
    });
    await user.click(confirm);
    expect(
      screen.getByRole('button', { name: 'Match confirmed' }),
    ).toBeDefined();

    await user.click(screen.getByRole('button', { name: /M11.*#149/ }));

    expect(
      screen.getByRole('button', { name: 'Confirm match' }),
    ).toHaveProperty('disabled', false);
    expect(screen.getByText('0 of 1 confirmed')).toBeDefined();
  });

  it('loads more alternatives and keeps search to one page', async () => {
    const user = userEvent.setup();
    mockSuggestionLookup(
      firstCard,
      [firstCard, secondCard],
      'alternative-next',
    );
    const findAlternatives = vi.spyOn(apiClient, 'findCatalogAlternatives');
    findAlternatives.mockResolvedValueOnce({
      cards: [firstCard, secondCard],
      next_continuation: 'alternative-next',
    });
    findAlternatives.mockResolvedValueOnce({
      cards: [thirdCard],
      next_continuation: null,
    });
    const findCards = vi.spyOn(apiClient, 'findCatalogCards');
    findCards.mockResolvedValueOnce({
      cards: [firstCard, secondCard],
      next_continuation: 'search-next',
    });

    renderReview(scan([row()]));
    await screen.findByRole('button', { name: /M11.*#149/ });
    await user.click(
      screen.getByRole('button', { name: 'Load more printings' }),
    );
    await screen.findByRole('button', { name: /STA.*#42/ });
    expect(
      within(screen.getByLabelText('Printings')).getAllByRole('button'),
    ).toHaveLength(3);

    const searchInput = screen.getByRole('textbox', {
      name: 'Search catalog cards',
    });
    await user.type(searchInput, 'Bolt');
    await waitFor(() =>
      expect(findCards).toHaveBeenCalledWith({
        game: 'mtg',
        query: 'Bolt',
        finish: 'normal',
      }),
    );
    await screen.findByLabelText('Catalog results');
    expect(
      within(screen.getByLabelText('Catalog results')).getAllByRole('button', {
        name: /Lightning Bolt/,
      }),
    ).toHaveLength(2);
    expect(findCards).toHaveBeenCalledTimes(1);
    expect(
      screen.queryByRole('button', { name: 'Load more results' }),
    ).toBeNull();

    await user.clear(searchInput);
    await user.type(searchInput, 'B');
    await waitFor(() =>
      expect(screen.queryByLabelText('Catalog results')).toBeNull(),
    );
  });

  it('keeps a valid product selectable after related-printing lookup fails', async () => {
    const user = userEvent.setup();
    vi.spyOn(apiClient, 'getCatalogCard').mockResolvedValue(firstCard);
    vi.spyOn(apiClient, 'findCatalogAlternatives').mockRejectedValue(
      new Error('catalog is temporarily unavailable'),
    );
    vi.spyOn(apiClient, 'findCatalogCards').mockResolvedValue({
      cards: [],
      next_continuation: null,
    });

    renderReview(scan([row()]));

    expect(
      await screen.findByText('catalog is temporarily unavailable'),
    ).toBeDefined();
    expect(screen.getAllByText('Double Masters 2022 · 2X2 #117')).toHaveLength(
      2,
    );
    const confirm = screen.getByRole('button', { name: 'Confirm match' });
    expect(confirm).toHaveProperty('disabled', false);
    await user.click(confirm);
    expect(screen.getByText('1 of 1 confirmed')).toBeDefined();
  });

  it('clears search results when the selected row changes', async () => {
    const user = userEvent.setup();
    mockSuggestionLookup();
    vi.spyOn(apiClient, 'findCatalogCards').mockResolvedValue({
      cards: [thirdCard],
      next_continuation: null,
    });

    renderReview(scan([row(), row({ scan_position: 2, filename: '002.jpg' })]));

    const searchInput = screen.getByRole('textbox', {
      name: 'Search catalog cards',
    });
    await user.type(searchInput, 'Archive');
    await screen.findByLabelText('Catalog results');
    await user.click(screen.getByRole('button', { name: /^2 Lightning Bolt/ }));

    await waitFor(() => {
      expect(searchInput).toHaveProperty('value', '');
      expect(screen.queryByLabelText('Catalog results')).toBeNull();
    });
  });

  it('shows a missing-image state and uses game-configured image regions', async () => {
    const noImage = card('product-1', {
      image_urls: { small: null, normal: null },
    });
    mockSuggestionLookup(noImage, [noImage]);
    const games: Game[] = [
      {
        ...REGISTERED_GAMES[0],
        scan_review_image_regions: [
          {
            id: 'collector_number',
            display_name: 'Collector number',
            x: 0.1,
            y: 0.9,
            width: 0.3,
            height: 0.06,
          },
        ],
      },
    ];

    renderReview(scan([row()]), { games });

    expect(
      await screen.findByRole('img', {
        name: 'Lightning Bolt reference unavailable',
      }),
    ).toBeDefined();
    expect(screen.getByText('Your scan · Collector number')).toBeDefined();
    expect(screen.getByText('Reference · Collector number')).toBeDefined();
    expect(screen.queryByText('Your scan · Set code')).toBeNull();
  });

  it('submits the catalog identity and metadata for every confirmed row', async () => {
    const user = userEvent.setup();
    const secondRow = row({
      scan_position: 2,
      filename: '002.jpg',
      suggestions: [
        {
          external_id: secondCard.external_id,
          name: secondCard.name,
          score: 0.7,
        },
      ],
    });
    vi.spyOn(apiClient, 'getCatalogCard').mockImplementation(
      async (_game, externalId) =>
        externalId === firstCard.external_id ? firstCard : secondCard,
    );
    vi.spyOn(apiClient, 'findCatalogAlternatives').mockImplementation(
      async ({ external_id }) => ({
        cards: [external_id === firstCard.external_id ? firstCard : secondCard],
        next_continuation: null,
      }),
    );
    vi.spyOn(apiClient, 'findCatalogCards').mockResolvedValue({
      cards: [],
      next_continuation: null,
    });
    const onConfirmScan = vi.fn().mockResolvedValue(undefined);
    renderReview(scan([row(), secondRow]), { onConfirmScan });

    await user.click(
      await screen.findByRole('button', { name: 'Confirm match' }),
    );
    await user.click(
      await screen.findByRole('button', { name: 'Confirm match' }),
    );
    await user.click(screen.getByRole('button', { name: 'Confirm scan' }));

    await waitFor(() => expect(onConfirmScan).toHaveBeenCalledTimes(1));
    expect(onConfirmScan).toHaveBeenCalledWith([
      {
        scan_position: 1,
        external_id: 'product-1',
        name: 'Lightning Bolt',
        set_code: '2x2',
        set_name: 'Double Masters 2022',
        collector_number: '117',
      },
      {
        scan_position: 2,
        external_id: 'product-2',
        name: 'Lightning Bolt',
        set_code: 'm11',
        set_name: 'Magic 2011',
        collector_number: '149',
      },
    ]);
  });

  it('preserves row navigation, product shortcuts, and explicit deletion', async () => {
    const user = userEvent.setup();
    mockSuggestionLookup();
    const onDeleteRow = vi.fn().mockResolvedValue(undefined);
    renderReview(
      scan([row(), row({ scan_position: 2, filename: '002.jpg' })]),
      {
        onDeleteRow,
      },
    );

    await screen.findByRole('button', { name: 'Confirm match' });
    await user.keyboard('j');
    expect(screen.getByText('Card 2 of 2')).toBeDefined();
    await user.keyboard('k');
    await user.keyboard('l');
    expect(
      screen
        .getByRole('button', { name: /Lightning Bolt.*M11 #149/ })
        .getAttribute('aria-pressed'),
    ).toBe('true');
    await user.keyboard('/');
    expect(document.activeElement).toBe(
      screen.getByRole('textbox', { name: 'Search catalog cards' }),
    );
    await user.keyboard('{Escape}');
    await user.click(screen.getByRole('button', { name: 'Delete card' }));
    await waitFor(() => expect(onDeleteRow).toHaveBeenCalledWith(1));
  });
});
