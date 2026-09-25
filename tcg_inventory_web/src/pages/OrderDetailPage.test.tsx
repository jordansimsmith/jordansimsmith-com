import {
  render,
  screen,
  cleanup,
  fireEvent,
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
    buyer_name: null,
    buyer_address: null,
    postage_option: null,
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
        scryfall_id: '58b26011-e103-45c4-a253-900f4e6b2eeb',
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
        scryfall_id: '58b26011-e103-45c4-a253-900f4e6b2eeb',
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
        scryfall_id: 'f0a51425-d796-48b8-b68c-bc21fb465c81',
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
    const fetchOrderLink = screen.getByRole('link', {
      name: 'View in FetchTCG',
    });
    expect(fetchOrderLink.getAttribute('href')).toBe(
      'https://www.fetchtcg.com/profile/sales/83647',
    );
    expect(fetchOrderLink.getAttribute('target')).toBe('_blank');
    expect(fetchOrderLink.getAttribute('rel')).toBe('noopener noreferrer');
    expect(fetchOrderLink.getAttribute('data-variant')).toBe('default');
    expect(screen.getByText('to pick')).toBeDefined();
    expect(screen.getByText('Total $10.90')).toBeDefined();
    expect(screen.getByText('Offered $10.90 · Listed $13.00')).toBeDefined();
    expect(screen.getByText('−16% vs list')).toBeDefined();
    expect(screen.queryByText('Offer')).toBeNull();
    expect(screen.getByText('Pull sheet')).toBeDefined();
    const summary = screen.getByRole('region', { name: 'Order summary' });
    expect(within(summary).getByText('to pick')).toBeDefined();
    expect(
      within(summary).getByText('Offered $10.90 · Listed $13.00'),
    ).toBeDefined();
    const pullSheet = screen.getByRole('region', { name: 'Pull sheet' });
    expect(
      within(pullSheet).getByRole('button', { name: 'Confirm pull' }),
    ).toBeDefined();

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

    const images = Array.from(
      document.querySelectorAll<HTMLImageElement>('img'),
    );
    expect(images).toHaveLength(3);
    expect(images.map((image) => image.src)).toEqual([
      'https://api.scryfall.com/cards/58b26011-e103-45c4-a253-900f4e6b2eeb?format=image&version=small',
      'https://api.scryfall.com/cards/58b26011-e103-45c4-a253-900f4e6b2eeb?format=image&version=small',
      'https://api.scryfall.com/cards/f0a51425-d796-48b8-b68c-bc21fb465c81?format=image&version=small',
    ]);
    expect(
      images.every((image) => image.getAttribute('loading') === 'lazy'),
    ).toBe(true);
    expect(
      images.every((image) => image.getAttribute('decoding') === 'async'),
    ).toBe(true);
    expect(
      images.every((image) => image.getAttribute('fetchpriority') === 'low'),
    ).toBe(true);
  });

  it('renders the buyer, address, and postage option', async () => {
    vi.spyOn(clientModule.apiClient, 'getOrder').mockResolvedValue(
      orderDetail({
        delivery_mode: 'DELIVERY',
        buyer_name: 'Mira Quasar (velvet-otter)',
        buyer_address: {
          line1: '7315 Marble Comet Drive',
          line2: 'Unit 3',
          suburb: 'Glintmere',
          city: 'Cloudmere',
          post_code: '0000',
          country: 'ZZ',
        },
        postage_option: 'Economy Tracked',
      }),
    );

    renderOrderDetailPage();

    expect(await screen.findByText('Mira Quasar (velvet-otter)')).toBeDefined();
    const delivery = screen.getByRole('region', { name: 'Delivery' });
    expect(
      within(delivery).getByText('Mira Quasar (velvet-otter)'),
    ).toBeDefined();
    expect(screen.getByText('7315 Marble Comet Drive')).toBeDefined();
    expect(screen.getByText('Unit 3')).toBeDefined();
    expect(screen.getByText('Glintmere, Cloudmere 0000')).toBeDefined();
    expect(screen.getByText('ZZ')).toBeDefined();
    expect(screen.getByText('Economy Tracked')).toBeDefined();
  });

  it('falls back to the delivery mode when there is no postage option', async () => {
    vi.spyOn(clientModule.apiClient, 'getOrder').mockResolvedValue(
      orderDetail(),
    );

    renderOrderDetailPage();

    expect(await screen.findByText('Pickup')).toBeDefined();
    expect(screen.queryByText('7315 Marble Comet Drive')).toBeNull();
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

  it('renders neutral external actions in the right column for a fulfilled order', async () => {
    vi.spyOn(clientModule.apiClient, 'getOrder').mockResolvedValue(
      orderDetail({ state: 'fulfilled' }),
    );

    renderOrderDetailPage();

    expect(await screen.findByText('Order 83647')).toBeDefined();
    const actions = screen.getByRole('region', { name: 'Order actions' });
    const fetchOrderLink = within(actions).getByRole('link', {
      name: 'View in FetchTCG',
    });
    expect(fetchOrderLink.getAttribute('data-variant')).toBe('default');
    const courierLink = within(actions).getByRole('link', {
      name: 'Book a courier',
    });
    expect(courierLink.getAttribute('href')).toBe(
      'https://www.trademe.co.nz/a/marketplace/book-courier/select',
    );
    expect(courierLink.getAttribute('target')).toBe('_blank');
    expect(courierLink.getAttribute('rel')).toBe('noopener noreferrer');
    expect(courierLink.getAttribute('data-variant')).toBe('default');
    expect(within(actions).getByText('Order actions')).toBeDefined();
    expect(
      screen
        .getByRole('button', { name: 'Back to orders' })
        .getAttribute('data-variant'),
    ).toBe('subtle');
  });

  it('does not offer courier booking before an order is fulfilled', async () => {
    vi.spyOn(clientModule.apiClient, 'getOrder').mockResolvedValue(
      orderDetail({ state: 'to_pick' }),
    );

    renderOrderDetailPage();

    expect(await screen.findByText('Order 83647')).toBeDefined();
    expect(screen.queryByRole('link', { name: 'Book a courier' })).toBeNull();
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
            scryfall_id: '0bc3401f-935b-45ce-b1e6-300a5d9dfd4f',
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
    expect(document.querySelectorAll('img')).toHaveLength(3);
  });

  it('renders thumbnails for voided orders', async () => {
    vi.spyOn(clientModule.apiClient, 'getOrder').mockResolvedValue(
      orderDetail({ state: 'voided' }),
    );

    renderOrderDetailPage();

    expect(await screen.findByText('Order 83647')).toBeDefined();
    expect(screen.getByText('voided')).toBeDefined();
    expect(document.querySelectorAll('img')).toHaveLength(3);
  });

  it('uses a neutral fallback when a thumbnail fails to load', async () => {
    vi.spyOn(clientModule.apiClient, 'getOrder').mockResolvedValue(
      orderDetail(),
    );

    renderOrderDetailPage();

    await screen.findByText('Order 83647');
    fireEvent.error(document.querySelector('img')!);

    await waitFor(() => {
      expect(document.querySelector('img')?.getAttribute('src')).toContain(
        'data:image/svg+xml',
      );
    });
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
