import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ScanDetail, ScanRow } from '../api/client';
import * as scryfallModule from '../api/scryfall-client';
import type { ScryfallPrinting } from '../api/scryfall-client';
import { ScanReview } from './ScanReview';

const printings: ScryfallPrinting[] = [
  {
    id: 'printing-1',
    name: 'Lightning Bolt',
    set_code: '2x2',
    set_name: 'Double Masters 2022',
    collector_number: '117',
    image_url: 'https://img.example/lightning-1.jpg',
  },
  {
    id: 'printing-2',
    name: 'Lightning Bolt',
    set_code: 'm11',
    set_name: 'Magic 2011',
    collector_number: '149',
    image_url: 'https://img.example/lightning-2.jpg',
  },
];

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
        scryfall_id: 'printing-1',
        name: 'Lightning Bolt',
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
    status: 'reviewing',
    condition: 'NM',
    finish: 'normal',
    row_count: rows.length,
    processed_count: rows.length,
    error: null,
    import_id: null,
    created_at: 1765420932,
    rows,
  };
}

function renderReview(detail: ScanDetail) {
  return render(
    <MantineProvider>
      <ScanReview scan={detail} />
    </MantineProvider>,
  );
}

describe('ScanReview', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('renders the actual source URL and advisory suggestion without a score', async () => {
    vi.spyOn(
      scryfallModule.scryfallClient,
      'getPrintingsForId',
    ).mockResolvedValue(printings);

    renderReview(scan([row()]));

    expect(await screen.findAllByText('Lightning Bolt')).not.toHaveLength(0);
    expect(screen.getByAltText('Scanned 001.jpg')).toHaveProperty(
      'src',
      'data:image/svg+xml,source',
    );
    expect(screen.getAllByText('Suggested match').length).toBeGreaterThan(0);
    expect(screen.queryByText('98%')).toBeNull();
    expect(screen.queryByText(/scryfall/i)).toBeNull();
  });

  it('seeds the highest-ranked persisted suggestion without confirming it', async () => {
    vi.spyOn(
      scryfallModule.scryfallClient,
      'getPrintingsForId',
    ).mockResolvedValue(printings);

    renderReview(
      scan([
        row({
          suggestions: [
            { ...row().suggestions[0], score: 0.4 },
            {
              scryfall_id: 'printing-2',
              name: 'Lightning Bolt',
              score: 0.99,
            },
          ],
        }),
      ]),
    );

    expect(
      (await screen.findByRole('button', { name: /M11.*#149/ })).getAttribute(
        'aria-pressed',
      ),
    ).toBe('true');
    expect(
      screen.getByRole('button', { name: 'Confirm match' }),
    ).not.toHaveProperty('disabled', true);
  });

  it('requires explicit confirmation and advances to the next unconfirmed row', async () => {
    vi.spyOn(
      scryfallModule.scryfallClient,
      'getPrintingsForId',
    ).mockResolvedValue(printings);
    const user = userEvent.setup();

    renderReview(
      scan([
        row(),
        row({
          scan_position: 2,
          filename: '002.jpg',
          suggestions: [
            { scryfall_id: 'printing-1', name: 'Lightning Bolt', score: 0.7 },
          ],
        }),
      ]),
    );

    const confirm = await screen.findByRole('button', {
      name: 'Confirm match',
    });
    await waitFor(() => expect(confirm).not.toHaveProperty('disabled', true));
    await user.click(confirm);

    expect(screen.getByText('1 of 2 confirmed')).toBeDefined();
    expect(screen.getByText('Card 2 of 2')).toBeDefined();
  });

  it('clears confirmation when the exact printing changes', async () => {
    vi.spyOn(
      scryfallModule.scryfallClient,
      'getPrintingsForId',
    ).mockResolvedValue(printings);
    const user = userEvent.setup();

    renderReview(scan([row()]));

    const confirm = await screen.findByRole('button', {
      name: 'Confirm match',
    });
    await waitFor(() => expect(confirm).not.toHaveProperty('disabled', true));
    await user.click(confirm);
    expect(
      screen.getByRole('button', { name: 'Match confirmed' }),
    ).toHaveProperty('disabled', true);

    await user.click(screen.getByRole('button', { name: /2X2.*#117/ }));
    expect(
      screen.getByRole('button', { name: 'Match confirmed' }),
    ).toHaveProperty('disabled', true);

    await user.click(screen.getByRole('button', { name: 'Next printing' }));

    expect(
      screen.getByRole('button', { name: 'Confirm match' }),
    ).not.toHaveProperty('disabled', true);
    expect(screen.getByText('M11')).toBeDefined();
  });

  it('supports manual search and replaces a wrong suggestion', async () => {
    const getPrintingsForId = vi
      .spyOn(scryfallModule.scryfallClient, 'getPrintingsForId')
      .mockResolvedValue(printings);
    vi.spyOn(scryfallModule.scryfallClient, 'autocomplete').mockResolvedValue([
      'Counterspell',
    ]);
    vi.spyOn(
      scryfallModule.scryfallClient,
      'getPrintingsByName',
    ).mockResolvedValue([
      {
        ...printings[0],
        id: 'counterspell-1',
        name: 'Counterspell',
        set_code: 'fdn',
        collector_number: '153',
      },
    ]);
    const user = userEvent.setup();

    renderReview(scan([row()]));
    await screen.findAllByText('Lightning Bolt');
    expect(getPrintingsForId).toHaveBeenCalledWith('printing-1');

    const search = screen.getByRole('textbox', {
      name: 'Search for a card by name',
    });
    await user.type(search, 'Counter');
    expect(
      await screen.findByRole('button', { name: 'Counterspell' }),
    ).toBeDefined();
    await user.click(screen.getByRole('button', { name: 'Counterspell' }));

    expect(await screen.findAllByText('Counterspell')).not.toHaveLength(0);
    expect(
      screen.getByRole('button', { name: 'Confirm match' }),
    ).not.toHaveProperty('disabled', true);
  });

  it('marks rows without a suggestion as needing manual selection', async () => {
    renderReview(
      scan([
        row({
          suggestions: [],
          needs_review: true,
          status: 'needs_review',
          source_url: null,
          error: 'recognition was inconclusive',
        }),
      ]),
    );

    expect(
      await screen.findAllByText('Manual selection required'),
    ).not.toHaveLength(0);
    expect(screen.getAllByText('Needs review').length).toBeGreaterThan(0);
    expect(screen.getByText('recognition was inconclusive')).toBeDefined();
    expect(
      screen.getByRole('button', { name: 'Confirm match' }),
    ).toHaveProperty('disabled', true);
  });

  it('shows card lookup failures and blocks confirmation', async () => {
    vi.spyOn(
      scryfallModule.scryfallClient,
      'getPrintingsForId',
    ).mockRejectedValue(new Error('Scryfall request failed (503)'));

    renderReview(scan([row()]));

    expect(
      await screen.findByText('card lookup request failed (503)'),
    ).toBeDefined();
    expect(screen.getAllByText('Needs review').length).toBeGreaterThan(0);
    expect(
      screen.getByRole('button', { name: 'Confirm match' }),
    ).toHaveProperty('disabled', true);
  });

  it('supports keyboard row, printing, search, and confirmation controls', async () => {
    vi.spyOn(
      scryfallModule.scryfallClient,
      'getPrintingsForId',
    ).mockResolvedValue(printings);
    const user = userEvent.setup();

    renderReview(scan([row(), row({ scan_position: 2, filename: '002.jpg' })]));
    const confirm = await screen.findByRole('button', {
      name: 'Confirm match',
    });
    await waitFor(() => expect(confirm).not.toHaveProperty('disabled', true));

    await user.keyboard('j');
    expect(screen.getByText('Card 2 of 2')).toBeDefined();
    await user.keyboard('k');
    expect(screen.getByText('Card 1 of 2')).toBeDefined();
    await user.keyboard('l');
    expect(screen.getByText('M11')).toBeDefined();
    await user.keyboard('h');
    expect(screen.getByText('2X2')).toBeDefined();
    await user.keyboard('/');
    const search = screen.getByRole('textbox', {
      name: 'Search for a card by name',
    });
    expect(document.activeElement).toBe(search);
    await user.keyboard('j');
    expect(screen.getByText('Card 1 of 2')).toBeDefined();
    await user.keyboard('{Escape}');
    expect(document.activeElement).not.toBe(search);
    await user.keyboard('c');
    expect(screen.getByText('1 of 2 confirmed')).toBeDefined();
    expect(screen.getByText('Card 2 of 2')).toBeDefined();
  });

  it('keeps shortcuts active after selecting a queue row with the pointer', async () => {
    vi.spyOn(
      scryfallModule.scryfallClient,
      'getPrintingsForId',
    ).mockResolvedValue(printings);
    const user = userEvent.setup();

    renderReview(
      scan([
        row(),
        row({ scan_position: 2, filename: '002.jpg' }),
        row({ scan_position: 3, filename: '003.jpg' }),
      ]),
    );
    const firstRow = screen
      .getByRole('region', { name: 'Scan cards' })
      .querySelector('button');
    expect(firstRow).not.toBeNull();
    await user.click(firstRow!);
    await user.keyboard('j');

    expect(screen.getByText('Card 2 of 3')).toBeDefined();
  });

  it('resets manual confirmation after a remount', async () => {
    vi.spyOn(
      scryfallModule.scryfallClient,
      'getPrintingsForId',
    ).mockResolvedValue(printings);
    const user = userEvent.setup();
    const rendered = renderReview(scan([row()]));
    const confirm = await screen.findByRole('button', {
      name: 'Confirm match',
    });
    await waitFor(() => expect(confirm).not.toHaveProperty('disabled', true));
    await user.click(confirm);
    expect(
      screen.getByRole('button', { name: 'Match confirmed' }),
    ).toBeDefined();

    rendered.unmount();
    renderReview(scan([row()]));
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'Confirm match' }),
      ).not.toHaveProperty('disabled', true),
    );
  });
});
