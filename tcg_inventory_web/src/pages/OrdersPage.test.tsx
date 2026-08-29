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
import { OrdersPage } from './OrdersPage';
import * as clientModule from '../api/client';
import type { OrderSummary } from '../api/client';

const orderFixtures: OrderSummary[] = [
  {
    order_id: '83663',
    state: 'awaiting_payment',
    accepted_at: 1765420932,
    delivery_mode: 'SHIPPING',
    total_price: '479.90',
    items_total_price: '479.90',
    listed_total_price: '431.50',
    unit_count: 6,
  },
  {
    order_id: '83647',
    state: 'to_pick',
    accepted_at: 1765334532,
    delivery_mode: 'PICKUP',
    total_price: '10.90',
    items_total_price: '10.90',
    listed_total_price: '13.00',
    unit_count: 3,
  },
  {
    order_id: '83611',
    state: 'fulfilled',
    accepted_at: 1765161732,
    delivery_mode: 'SHIPPING',
    total_price: '8.50',
    items_total_price: '8.50',
    listed_total_price: '8.50',
    unit_count: 1,
  },
  {
    order_id: '83598',
    state: 'voided',
    accepted_at: 1764988932,
    delivery_mode: 'PICKUP',
    total_price: '4.20',
    items_total_price: '4.20',
    listed_total_price: null,
    unit_count: 1,
  },
];

function OrderDetailStub() {
  const { orderId } = useParams<{ orderId: string }>();
  return <div>Order detail {orderId}</div>;
}

function renderOrdersPage() {
  return render(
    <MantineProvider>
      <Notifications />
      <MemoryRouter initialEntries={['/orders']}>
        <Routes>
          <Route path="/orders" element={<OrdersPage />} />
          <Route path="/orders/:orderId" element={<OrderDetailStub />} />
        </Routes>
      </MemoryRouter>
    </MantineProvider>,
  );
}

describe('OrdersPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(clientModule.apiClient, 'findOrders').mockResolvedValue({
      orders: orderFixtures,
      next_continuation: null,
    });
  });

  afterEach(() => {
    cleanup();
  });

  it('renders order rows with state badges and totals', async () => {
    renderOrdersPage();

    const row = (await screen.findByText('83647')).closest(
      'tr',
    ) as HTMLTableRowElement;
    expect(within(row).getByText('to pick')).toBeDefined();
    expect(within(row).getByText('3')).toBeDefined();
    expect(within(row).getByText('$10.90')).toBeDefined();
    expect(within(row).getByText('PICKUP')).toBeDefined();
    expect(within(row).queryByText(/vs list/)).toBeNull();

    const awaitingRow = screen
      .getByText('83663')
      .closest('tr') as HTMLTableRowElement;
    expect(within(awaitingRow).getByText('awaiting payment')).toBeDefined();
    const fulfilledRow = screen
      .getByText('83611')
      .closest('tr') as HTMLTableRowElement;
    expect(within(fulfilledRow).getByText('fulfilled')).toBeDefined();
    const voidedRow = screen
      .getByText('83598')
      .closest('tr') as HTMLTableRowElement;
    expect(within(voidedRow).getByText('voided')).toBeDefined();
  });

  it('shows an empty state when there are no orders', async () => {
    vi.spyOn(clientModule.apiClient, 'findOrders').mockResolvedValue({
      orders: [],
      next_continuation: null,
    });

    renderOrdersPage();

    expect(await screen.findByText('No orders yet.')).toBeDefined();
  });

  it('shows the error when orders fail to load', async () => {
    vi.spyOn(clientModule.apiClient, 'findOrders').mockRejectedValue(
      new Error('Request failed'),
    );

    renderOrdersPage();

    const messages = await screen.findAllByText('Request failed');
    expect(messages.length).toBeGreaterThan(0);
  });

  it('moves the selection with j and k', async () => {
    const user = userEvent.setup();
    renderOrdersPage();
    await screen.findByText('83663');

    const rowFor = (orderId: string) => screen.getByText(orderId).closest('tr');
    expect(rowFor('83663')?.getAttribute('data-selected')).toBe('true');

    await user.keyboard('j');
    expect(rowFor('83647')?.getAttribute('data-selected')).toBe('true');

    await user.keyboard('k');
    expect(rowFor('83663')?.getAttribute('data-selected')).toBe('true');
  });

  it('opens the selected order with Enter', async () => {
    const user = userEvent.setup();
    renderOrdersPage();
    await screen.findByText('83663');

    await user.keyboard('j');
    await user.keyboard('{Enter}');

    await waitFor(() => {
      expect(screen.getByText('Order detail 83647')).toBeDefined();
    });
  });

  it('navigates when a row is clicked', async () => {
    const user = userEvent.setup();
    renderOrdersPage();
    await screen.findByText('83611');

    await user.click(screen.getByText('83611'));

    await waitFor(() => {
      expect(screen.getByText('Order detail 83611')).toBeDefined();
    });
  });

  it('appends the next page when load more is clicked', async () => {
    const nextPage: OrderSummary = {
      order_id: '83500',
      state: 'fulfilled',
      accepted_at: 1764802532,
      delivery_mode: 'SHIPPING',
      total_price: '2.00',
      items_total_price: '2.00',
      listed_total_price: '2.00',
      unit_count: 2,
    };
    vi.spyOn(clientModule.apiClient, 'findOrders').mockImplementation(
      async (params) => {
        if (params?.continuation === 'page-2') {
          return { orders: [nextPage], next_continuation: null };
        }
        return { orders: orderFixtures, next_continuation: 'page-2' };
      },
    );
    const user = userEvent.setup();
    renderOrdersPage();
    await screen.findByText('83663');
    expect(screen.queryByText('83500')).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Load more' }));

    expect(await screen.findByText('83500')).toBeDefined();
    expect(clientModule.apiClient.findOrders).toHaveBeenLastCalledWith({
      continuation: 'page-2',
    });
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
  });
});
