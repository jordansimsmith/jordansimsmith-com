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
import { MemoryRouter, Routes, Route, useParams } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { InventoryPage } from './InventoryPage';
import * as clientModule from '../api/client';
import type { SkuSummary } from '../api/client';

const skuFixtures: SkuSummary[] = [
  {
    sku_id: '11111111-1111-4111-8111-111111111111#normal#NM',
    name: 'Lightning Bolt',
    set_code: 'sta',
    set_name: 'Strixhaven Mystical Archive',
    collector_number: '42',
    finish: 'normal',
    condition: 'NM',
    in_stock_count: 4,
    reserved_count: 1,
  },
  {
    sku_id: '22222222-2222-4222-8222-222222222222#foil#LP',
    name: 'Opt',
    set_code: 'dom',
    set_name: 'Dominaria',
    collector_number: '60',
    finish: 'foil',
    condition: 'LP',
    in_stock_count: 5,
    reserved_count: 0,
  },
  {
    sku_id: '33333333-3333-4333-8333-333333333333#normal#MP',
    name: 'Sol Ring',
    set_code: 'cmr',
    set_name: 'Commander Legends',
    collector_number: '331',
    finish: 'normal',
    condition: 'MP',
    in_stock_count: 6,
    reserved_count: 2,
  },
];

function SkuDetailStub() {
  const { skuId } = useParams<{ skuId: string }>();
  return <div>SKU detail {skuId}</div>;
}

function renderInventoryPage() {
  return render(
    <MantineProvider>
      <Notifications />
      <MemoryRouter initialEntries={['/inventory']}>
        <Routes>
          <Route path="/inventory" element={<InventoryPage />} />
          <Route path="/inventory/:skuId" element={<SkuDetailStub />} />
        </Routes>
      </MemoryRouter>
    </MantineProvider>,
  );
}

describe('InventoryPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(clientModule.apiClient, 'findSkus').mockImplementation(
      async (params) => {
        const search = params?.search?.toLowerCase() ?? '';
        return {
          skus: skuFixtures.filter((sku) =>
            sku.name.toLowerCase().startsWith(search),
          ),
          next_continuation: null,
        };
      },
    );
  });

  afterEach(() => {
    cleanup();
  });

  it('renders SKU rows with their fields', async () => {
    renderInventoryPage();

    const row = (await screen.findByText('Lightning Bolt')).closest(
      'tr',
    ) as HTMLTableRowElement;

    expect(within(row).getByText('STA')).toBeDefined();
    expect(within(row).getByText('STA').getAttribute('title')).toBe(
      'Strixhaven Mystical Archive',
    );
    expect(within(row).getByText('42')).toBeDefined();
    expect(within(row).getByText('normal')).toBeDefined();
    expect(within(row).getByText('NM')).toBeDefined();
    expect(within(row).getByText('4')).toBeDefined();
    expect(within(row).getByText('1')).toBeDefined();
    expect(screen.getByText('Opt')).toBeDefined();
    expect(screen.getByText('Sol Ring')).toBeDefined();
  });

  it('filters rows with prefix search', async () => {
    const user = userEvent.setup();
    renderInventoryPage();
    await screen.findByText('Lightning Bolt');

    await user.type(screen.getByLabelText('Search SKUs'), 'sol');

    await waitFor(() => {
      expect(screen.queryByText('Lightning Bolt')).toBeNull();
    });
    expect(screen.getByText('Sol Ring')).toBeDefined();
    expect(clientModule.apiClient.findSkus).toHaveBeenLastCalledWith({
      search: 'sol',
    });
  });

  it('opens the selected row with Enter', async () => {
    const user = userEvent.setup();
    renderInventoryPage();
    await screen.findByText('Lightning Bolt');

    await user.keyboard('j');
    await user.keyboard('{Enter}');

    await waitFor(() => {
      expect(
        screen.getByText(`SKU detail ${skuFixtures[1].sku_id}`),
      ).toBeDefined();
    });
  });

  it('navigates when a row is clicked', async () => {
    const user = userEvent.setup();
    renderInventoryPage();
    await screen.findByText('Sol Ring');

    await user.click(screen.getByText('Sol Ring'));

    await waitFor(() => {
      expect(
        screen.getByText(`SKU detail ${skuFixtures[2].sku_id}`),
      ).toBeDefined();
    });
  });

  it('shows an empty state when no SKUs match', async () => {
    vi.spyOn(clientModule.apiClient, 'findSkus').mockResolvedValue({
      skus: [],
      next_continuation: null,
    });

    renderInventoryPage();

    expect(await screen.findByText('No SKUs found.')).toBeDefined();
  });
});
