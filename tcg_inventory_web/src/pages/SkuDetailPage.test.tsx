import {
  render,
  screen,
  waitFor,
  cleanup,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { SkuDetailPage } from './SkuDetailPage';
import * as clientModule from '../api/client';
import type { SkuDetail } from '../api/client';

const SCRYFALL_ID = 'aaaa1111-2222-4333-8444-555566667777';

const nmDetail: SkuDetail = {
  sku_id: `${SCRYFALL_ID}#normal#NM`,
  scryfall_id: SCRYFALL_ID,
  name: 'Sol Ring',
  set_code: 'cmr',
  set_name: 'Commander Legends',
  collector_number: '472',
  finish: 'normal',
  condition: 'NM',
  last_published_price: '2.50',
  in_stock_count: 2,
  reserved_count: 1,
  sold_count: 1,
  units: [
    { sequence_number: 3, location: 'A0-3', status: 'reserved' },
    { sequence_number: 40, location: 'A0-40', status: 'in_stock' },
    { sequence_number: 152, location: 'A1-52', status: 'in_stock' },
    { sequence_number: 480, location: 'A4-80', status: 'sold' },
  ],
};

const lpDetail: SkuDetail = {
  sku_id: `${SCRYFALL_ID}#normal#LP`,
  scryfall_id: SCRYFALL_ID,
  name: 'Sol Ring',
  set_code: 'cmr',
  set_name: 'Commander Legends',
  collector_number: '472',
  finish: 'normal',
  condition: 'LP',
  last_published_price: null,
  in_stock_count: 5,
  reserved_count: 0,
  sold_count: 0,
  units: [{ sequence_number: 40, location: 'A0-40', status: 'in_stock' }],
};

function renderSkuDetailPage(skuId = nmDetail.sku_id) {
  return render(
    <MantineProvider env="test">
      <Notifications />
      <MemoryRouter
        initialEntries={[`/inventory/${encodeURIComponent(skuId)}`]}
      >
        <Routes>
          <Route path="/inventory" element={<div>Inventory page</div>} />
          <Route path="/inventory/:skuId" element={<SkuDetailPage />} />
        </Routes>
      </MemoryRouter>
    </MantineProvider>,
  );
}

describe('SkuDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(clientModule.apiClient, 'getSku').mockImplementation(
      async (skuId) => {
        if (skuId === nmDetail.sku_id) {
          return structuredClone(nmDetail);
        }
        if (skuId === lpDetail.sku_id) {
          return structuredClone(lpDetail);
        }
        throw new Error('Not Found');
      },
    );
  });

  afterEach(() => {
    cleanup();
  });

  it('renders the SKU header, counts, card image, and units', async () => {
    renderSkuDetailPage();

    expect(
      await screen.findByRole('heading', { name: 'Sol Ring' }),
    ).toBeDefined();
    expect(screen.getByText(/Commander Legends \(CMR\)/)).toBeDefined();
    expect(screen.getByText('In stock: 2')).toBeDefined();
    expect(screen.getByText('Reserved: 1')).toBeDefined();
    expect(screen.getByText('Sold: 1')).toBeDefined();

    const image = screen.getByRole('img', { name: 'Sol Ring' });
    expect(image.getAttribute('src')).toBe(
      `https://api.scryfall.com/cards/${SCRYFALL_ID}?format=image&version=normal`,
    );

    for (const location of ['A0-3', 'A0-40', 'A1-52', 'A4-80']) {
      expect(screen.getByText(location)).toBeDefined();
    }
    expect(screen.getByText('reserved')).toBeDefined();
    expect(screen.getByText('sold')).toBeDefined();
    expect(screen.getAllByRole('button', { name: 'Remove' })).toHaveLength(2);
    expect(
      screen.getAllByRole('button', { name: 'Edit condition' }),
    ).toHaveLength(2);
  });

  it('removes a unit with a reason and re-renders counts', async () => {
    const updated: SkuDetail = {
      ...structuredClone(nmDetail),
      in_stock_count: 1,
      units: nmDetail.units.map((unit) =>
        unit.sequence_number === 40
          ? { ...unit, status: 'removed' as const }
          : unit,
      ),
    };
    vi.spyOn(clientModule.apiClient, 'deleteUnit').mockResolvedValue(undefined);
    vi.spyOn(clientModule.apiClient, 'getSku')
      .mockResolvedValueOnce(structuredClone(nmDetail))
      .mockResolvedValue(updated);

    const user = userEvent.setup();
    renderSkuDetailPage();
    await screen.findByRole('heading', { name: 'Sol Ring' });

    await user.click(screen.getAllByRole('button', { name: 'Remove' })[0]);
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/This cannot be undone/)).toBeDefined();

    await user.type(
      within(dialog).getByLabelText('Reason (optional)'),
      'damaged',
    );
    await user.click(within(dialog).getByRole('button', { name: 'Remove' }));

    await waitFor(() => {
      expect(screen.getByText('In stock: 1')).toBeDefined();
    });
    expect(clientModule.apiClient.deleteUnit).toHaveBeenCalledWith(
      nmDetail.sku_id,
      40,
      'damaged',
    );
    expect(screen.getByText('removed')).toBeDefined();
  });

  it('removes a unit without a reason', async () => {
    vi.spyOn(clientModule.apiClient, 'deleteUnit').mockResolvedValue(undefined);
    vi.spyOn(clientModule.apiClient, 'getSku')
      .mockResolvedValueOnce(structuredClone(nmDetail))
      .mockResolvedValue(structuredClone(nmDetail));

    const user = userEvent.setup();
    renderSkuDetailPage();
    await screen.findByRole('heading', { name: 'Sol Ring' });

    await user.click(screen.getAllByRole('button', { name: 'Remove' })[0]);
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Remove' }));

    await waitFor(() => {
      expect(clientModule.apiClient.deleteUnit).toHaveBeenCalledWith(
        nmDetail.sku_id,
        40,
        undefined,
      );
    });
  });

  it('changes a unit condition and navigates to the new SKU', async () => {
    vi.spyOn(clientModule.apiClient, 'updateUnit').mockResolvedValue({
      sku_id: lpDetail.sku_id,
    });

    const user = userEvent.setup();
    renderSkuDetailPage();
    await screen.findByRole('heading', { name: 'Sol Ring' });

    await user.click(
      screen.getAllByRole('button', { name: 'Edit condition' })[0],
    );
    const dialog = await screen.findByRole('dialog');
    await user.click(
      within(dialog).getByRole('textbox', { name: 'New condition' }),
    );
    await user.click(await screen.findByRole('option', { name: 'LP' }));
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));

    await waitFor(() => {
      expect(clientModule.apiClient.updateUnit).toHaveBeenCalledWith(
        nmDetail.sku_id,
        40,
        'LP',
      );
    });
    await waitFor(() => {
      expect(clientModule.apiClient.getSku).toHaveBeenLastCalledWith(
        lpDetail.sku_id,
      );
    });
    expect(await screen.findByText('In stock: 5')).toBeDefined();
  });

  it('navigates back to inventory on Escape', async () => {
    const user = userEvent.setup();
    renderSkuDetailPage();
    await screen.findByRole('heading', { name: 'Sol Ring' });

    await user.keyboard('{Escape}');

    expect(await screen.findByText('Inventory page')).toBeDefined();
  });

  it('shows an error state for an unknown SKU', async () => {
    const user = userEvent.setup();
    renderSkuDetailPage('missing#normal#NM');

    expect(await screen.findByText('Not Found')).toBeDefined();

    await user.click(screen.getByRole('button', { name: 'Back to inventory' }));
    expect(await screen.findByText('Inventory page')).toBeDefined();
  });
});
