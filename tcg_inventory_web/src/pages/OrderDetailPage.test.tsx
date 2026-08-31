import {
  render,
  screen,
  cleanup,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { OrderDetailPage } from './OrderDetailPage';
import * as clientModule from '../api/client';
import type { OrderDetail } from '../api/client';

function orderDetail(overrides: Partial<OrderDetail> = {}): OrderDetail {
  return {
    order_id: '83647',
    state: 'to_pick',
    accepted_at: 1765420932,
    delivery_mode: 'PICKUP',
    total_price: '10.90',
    items_total_price: '10.90',
    listed_total_price: '13.00',
    unit_count: 3,
    lines: [
      {
        name: 'Sol Ring',
        set_code: 'cmr',
        collector_number: '472',
        finish: 'normal',
        condition: 'NM',
        quantity: 2,
        price: '8.00',
        listed_price: '5.00',
      },
      {
        name: 'Elvish Aberration',
        set_code: 'a25',
        collector_number: '167',
        finish: 'foil',
        condition: 'NM',
        quantity: 1,
        price: '2.90',
        listed_price: '3.00',
      },
    ],
    units: [
      {
        sequence_number: 37,
        location: 'A0-37',
        current_location: 'A0-35',
        name: 'Sol Ring',
        set_code: 'cmr',
        collector_number: '472',
        finish: 'normal',
        condition: 'NM',
        price: '4.00',
        previous_card: {
          name: 'Llanowar Elves',
          set_code: 'dom',
          collector_number: '168',
          finish: 'normal',
          condition: 'NM',
        },
        next_card: {
          name: 'Sol Ring',
          set_code: 'cmr',
          collector_number: '472',
          finish: 'normal',
          condition: 'NM',
        },
      },
      {
        sequence_number: 74,
        location: 'A0-74',
        current_location: 'A0-70',
        name: 'Sol Ring',
        set_code: 'cmr',
        collector_number: '472',
        finish: 'normal',
        condition: 'NM',
        price: '4.00',
        previous_card: {
          name: 'Brainstorm',
          set_code: 'ema',
          collector_number: '40',
          finish: 'normal',
          condition: 'NM',
        },
        next_card: null,
      },
      {
        sequence_number: 259,
        location: 'A2-59',
        current_location: 'A2-59',
        name: 'Elvish Aberration',
        set_code: 'a25',
        collector_number: '167',
        finish: 'foil',
        condition: 'NM',
        price: '2.90',
        previous_card: null,
        next_card: {
          name: 'Counterspell',
          set_code: 'mh2',
          collector_number: '267',
          finish: 'normal',
          condition: 'NM',
        },
      },
    ],
    ...overrides,
  };
}

function renderOrderDetailPage() {
  return render(
    <MantineProvider>
      <Notifications />
      <MemoryRouter initialEntries={['/orders/83647']}>
        <Routes>
          <Route path="/orders/:orderId" element={<OrderDetailPage />} />
          <Route path="/orders" element={<div>Orders list</div>} />
        </Routes>
      </MemoryRouter>
    </MantineProvider>,
  );
}

describe('OrderDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  it('renders order meta and the pull sheet in location order', async () => {
    vi.spyOn(clientModule.apiClient, 'getOrder').mockResolvedValue(
      orderDetail(),
    );

    renderOrderDetailPage();

    expect(await screen.findByText('Order 83647')).toBeDefined();
    expect(screen.getByText('to pick')).toBeDefined();
    expect(screen.getByText('PICKUP · $10.90')).toBeDefined();
    expect(screen.getByText('Offered $10.90 · Listed $13.00')).toBeDefined();
    expect(screen.getByText('−16% vs list')).toBeDefined();
    expect(screen.queryByText('Offer')).toBeNull();
    expect(screen.getByText('Pull sheet')).toBeDefined();

    // current locations render big with the insertion location struck through
    // beside them when they differ
    const locations = screen
      .getAllByText(/^A\d+-\d+$/)
      .map((element) => element.textContent);
    expect(locations).toEqual(['A0-35', 'A0-37', 'A0-70', 'A0-74', 'A2-59']);
    expect(screen.getAllByText('$4.00')).toHaveLength(2);
    expect(screen.getByText('$2.90')).toBeDefined();
    expect(screen.getAllByText('Sol Ring')).toHaveLength(2);
    expect(screen.getByText('A25 #167 · NM · foil')).toBeDefined();
    expect(
      screen.getByText('Prev · Llanowar Elves · DOM #168 · NM'),
    ).toBeDefined();
    expect(screen.getByText('Next · Sol Ring · CMR #472 · NM')).toBeDefined();
    expect(screen.getByText('Prev · Brainstorm · EMA #40 · NM')).toBeDefined();
    expect(
      screen.getByText('Next · Counterspell · MH2 #267 · NM'),
    ).toBeDefined();
    expect(screen.getByRole('button', { name: 'Confirm pull' })).toBeDefined();
  });

  it('confirms the pull and renders the fulfilled order', async () => {
    const user = userEvent.setup();
    vi.spyOn(clientModule.apiClient, 'getOrder')
      .mockResolvedValueOnce(orderDetail())
      .mockResolvedValue(orderDetail({ state: 'fulfilled' }));
    vi.spyOn(clientModule.apiClient, 'confirmOrder').mockResolvedValue({
      order_id: '83647',
      state: 'fulfilled',
    });

    renderOrderDetailPage();
    await screen.findByText('Order 83647');

    await user.click(screen.getByRole('button', { name: 'Confirm pull' }));
    const dialog = await screen.findByRole('dialog');
    expect(
      within(dialog).getByText(/cards.*as pulled/, { exact: false }),
    ).toBeDefined();

    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));

    await waitFor(() => {
      expect(clientModule.apiClient.confirmOrder).toHaveBeenCalledWith('83647');
    });
    expect(await screen.findByText('fulfilled')).toBeDefined();
    expect(clientModule.apiClient.getOrder).toHaveBeenCalledTimes(2);
    expect(screen.getByText('Order fulfilled')).toBeDefined();
    expect(screen.getByText('Cards')).toBeDefined();
    expect(screen.queryByText('Pull sheet')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Confirm pull' })).toBeNull();

    // fulfilled orders show insertion locations only, without pull context
    const locations = screen
      .getAllByText(/^A\d+-\d+$/)
      .map((element) => element.textContent);
    expect(locations).toEqual(['A0-37', 'A0-74', 'A2-59']);
    expect(screen.queryByText(/^Prev ·/)).toBeNull();
    expect(screen.queryByText(/^Next ·/)).toBeNull();
  });

  it('surfaces confirm failures and stays on the pull sheet', async () => {
    const user = userEvent.setup();
    vi.spyOn(clientModule.apiClient, 'getOrder').mockResolvedValue(
      orderDetail(),
    );
    vi.spyOn(clientModule.apiClient, 'confirmOrder').mockRejectedValue(
      new Error('order is not ready to pick'),
    );

    renderOrderDetailPage();
    await screen.findByText('Order 83647');

    await user.click(screen.getByRole('button', { name: 'Confirm pull' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));

    expect(await screen.findByText('order is not ready to pick')).toBeDefined();
    expect(screen.getByText('Pull sheet')).toBeDefined();
    expect(screen.getByRole('button', { name: 'Confirm pull' })).toBeDefined();
  });

  it('omits the vs-list badge when the offer matches list', async () => {
    vi.spyOn(clientModule.apiClient, 'getOrder').mockResolvedValue(
      orderDetail({
        items_total_price: '8.50',
        listed_total_price: '8.50',
        lines: [
          {
            name: 'Hellkite Tyrant',
            set_code: 'gtc',
            collector_number: '94',
            finish: 'normal',
            condition: 'NM',
            quantity: 1,
            price: '8.50',
            listed_price: '8.50',
          },
        ],
        units: [
          {
            sequence_number: 1,
            location: 'A0-1',
            current_location: 'A0-1',
            name: 'Hellkite Tyrant',
            set_code: 'gtc',
            collector_number: '94',
            finish: 'normal',
            condition: 'NM',
            price: '8.50',
            previous_card: null,
            next_card: null,
          },
        ],
      }),
    );

    renderOrderDetailPage();

    expect(await screen.findByText('Order 83647')).toBeDefined();
    expect(screen.getByText('Offered $8.50 · Listed $8.50')).toBeDefined();
    expect(screen.queryByText(/vs list/)).toBeNull();
  });

  it('renders a non-pickable order without a confirm button', async () => {
    vi.spyOn(clientModule.apiClient, 'getOrder').mockResolvedValue(
      orderDetail({ state: 'awaiting_payment' }),
    );

    renderOrderDetailPage();

    expect(await screen.findByText('Order 83647')).toBeDefined();
    expect(screen.getByText('awaiting payment')).toBeDefined();
    expect(screen.getByText('Cards')).toBeDefined();
    expect(screen.queryByText('Pull sheet')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Confirm pull' })).toBeNull();
    // reserved cards are still boxed, so pull context renders before payment
    expect(screen.getByText('A0-35')).toBeDefined();
  });

  it('returns to the orders list on Escape', async () => {
    const user = userEvent.setup();
    vi.spyOn(clientModule.apiClient, 'getOrder').mockResolvedValue(
      orderDetail(),
    );

    renderOrderDetailPage();
    await screen.findByText('Order 83647');

    await user.keyboard('{Escape}');

    await waitFor(() => {
      expect(screen.getByText('Orders list')).toBeDefined();
    });
  });

  it('shows an error with a way back when the order fails to load', async () => {
    const user = userEvent.setup();
    vi.spyOn(clientModule.apiClient, 'getOrder').mockRejectedValue(
      new Error('Not Found'),
    );

    renderOrderDetailPage();

    expect(await screen.findByText('Not Found')).toBeDefined();
    await user.click(screen.getByRole('button', { name: 'Back to orders' }));
    await waitFor(() => {
      expect(screen.getByText('Orders list')).toBeDefined();
    });
  });
});
