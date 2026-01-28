import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { DatesProvider } from '@mantine/dates';
import { Notifications, notifications } from '@mantine/notifications';
import { MemoryRouter, Routes, Route, Navigate } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { TripPage } from './TripPage';
import { getSession } from '../auth/session';
import * as clientModule from '../api/client';

function RequireAuth({ children }: { children: React.ReactNode }) {
  const session = getSession();
  if (!session) {
    return <Navigate to="/" replace />;
  }
  return <>{children}</>;
}

function renderTripPage(tripId: string = 'trip-001') {
  return render(
    <MantineProvider>
      <DatesProvider settings={{}}>
        <Notifications />
        <MemoryRouter initialEntries={[`/trips/${tripId}`]}>
          <Routes>
            <Route path="/" element={<div>Login page</div>} />
            <Route path="/trips" element={<div>Trips page</div>} />
            <Route
              path="/trips/:tripId"
              element={
                <RequireAuth>
                  <TripPage />
                </RequireAuth>
              }
            />
          </Routes>
        </MemoryRouter>
      </DatesProvider>
    </MantineProvider>,
  );
}

describe('TripPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    sessionStorage.clear();
    notifications.clean();
  });

  afterEach(() => {
    cleanup();
    notifications.clean();
  });

  it('redirects to login when not authenticated', async () => {
    vi.spyOn(clientModule.apiClient, 'getTrip').mockResolvedValue({
      trip: {
        trip_id: 'trip-001',
        name: 'Japan 2025',
        destination: 'Tokyo',
        departure_date: '2025-03-15',
        return_date: '2025-03-29',
        items: [],
        created_at: 1735000000,
        updated_at: 1735000000,
      },
    });
    renderTripPage();

    await waitFor(() => {
      expect(screen.getByText('Login page')).toBeDefined();
    });
  });

  it('displays trip details when loaded', async () => {
    sessionStorage.setItem(
      'packing_list_auth',
      JSON.stringify({
        username: 'testuser',
        token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
      }),
    );

    vi.spyOn(clientModule.apiClient, 'getTrip').mockResolvedValue({
      trip: {
        trip_id: 'trip-001',
        name: 'Japan 2025',
        destination: 'Tokyo',
        departure_date: '2025-03-15',
        return_date: '2025-03-29',
        items: [
          {
            name: 'Passport',
            category: 'travel',
            quantity: 1,
            tags: ['hand luggage'],
            status: 'unpacked',
          },
          {
            name: 'Toothbrush',
            category: 'toiletries',
            quantity: 1,
            tags: [],
            status: 'packed',
          },
        ],
        created_at: 1735000000,
        updated_at: 1735000000,
      },
    });

    renderTripPage();

    await waitFor(() => {
      expect(screen.getByText('Japan 2025')).toBeDefined();
    });
    expect(screen.getByText('Tokyo')).toBeDefined();
    expect(screen.getByText('Passport')).toBeDefined();
    expect(screen.getByText('Toothbrush')).toBeDefined();
    expect(screen.getByText('hand luggage')).toBeDefined();
  });

  it('groups items by category and sorts categories alphabetically', async () => {
    sessionStorage.setItem(
      'packing_list_auth',
      JSON.stringify({
        username: 'testuser',
        token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
      }),
    );

    vi.spyOn(clientModule.apiClient, 'getTrip').mockResolvedValue({
      trip: {
        trip_id: 'trip-001',
        name: 'Test Trip',
        destination: 'Destination',
        departure_date: '2025-03-15',
        return_date: '2025-03-29',
        items: [
          {
            name: 'Toothbrush',
            category: 'toiletries',
            quantity: 1,
            tags: [],
            status: 'unpacked',
          },
          {
            name: 'Laptop',
            category: 'electronics',
            quantity: 1,
            tags: [],
            status: 'unpacked',
          },
          {
            name: 'Passport',
            category: 'travel',
            quantity: 1,
            tags: [],
            status: 'unpacked',
          },
        ],
        created_at: 1735000000,
        updated_at: 1735000000,
      },
    });

    renderTripPage();

    await waitFor(() => {
      expect(screen.getByText('Test Trip')).toBeDefined();
    });

    const categoryHeadings = screen.getAllByText(
      /electronics|toiletries|travel/i,
    );
    expect(categoryHeadings[0].textContent?.toLowerCase()).toBe('electronics');
    expect(categoryHeadings[1].textContent?.toLowerCase()).toBe('toiletries');
    expect(categoryHeadings[2].textContent?.toLowerCase()).toBe('travel');
  });

  it('sorts misc category last', async () => {
    sessionStorage.setItem(
      'packing_list_auth',
      JSON.stringify({
        username: 'testuser',
        token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
      }),
    );

    vi.spyOn(clientModule.apiClient, 'getTrip').mockResolvedValue({
      trip: {
        trip_id: 'trip-001',
        name: 'Test Trip',
        destination: 'Destination',
        departure_date: '2025-03-15',
        return_date: '2025-03-29',
        items: [
          {
            name: 'Drink bottle',
            category: 'misc',
            quantity: 1,
            tags: [],
            status: 'unpacked',
          },
          {
            name: 'Laptop',
            category: 'electronics',
            quantity: 1,
            tags: [],
            status: 'unpacked',
          },
          {
            name: 'Passport',
            category: 'travel',
            quantity: 1,
            tags: [],
            status: 'unpacked',
          },
        ],
        created_at: 1735000000,
        updated_at: 1735000000,
      },
    });

    renderTripPage();

    await waitFor(() => {
      expect(screen.getByText('Test Trip')).toBeDefined();
    });

    const categoryHeadings = screen.getAllByText(/electronics|travel|misc/i);
    expect(categoryHeadings[0].textContent?.toLowerCase()).toBe('electronics');
    expect(categoryHeadings[1].textContent?.toLowerCase()).toBe('travel');
    expect(categoryHeadings[2].textContent?.toLowerCase()).toBe('misc');
  });

  it('hides packed items when hide packed toggle is enabled', async () => {
    const user = userEvent.setup();

    sessionStorage.setItem(
      'packing_list_auth',
      JSON.stringify({
        username: 'testuser',
        token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
      }),
    );

    vi.spyOn(clientModule.apiClient, 'getTrip').mockResolvedValue({
      trip: {
        trip_id: 'trip-001',
        name: 'Test Trip',
        destination: 'Destination',
        departure_date: '2025-03-15',
        return_date: '2025-03-29',
        items: [
          {
            name: 'Passport',
            category: 'travel',
            quantity: 1,
            tags: [],
            status: 'unpacked',
          },
          {
            name: 'Toothbrush',
            category: 'toiletries',
            quantity: 1,
            tags: [],
            status: 'packed',
          },
        ],
        created_at: 1735000000,
        updated_at: 1735000000,
      },
    });

    renderTripPage();

    await waitFor(() => {
      expect(screen.getByText('Passport')).toBeDefined();
    });
    expect(screen.getByText('Toothbrush')).toBeDefined();

    const toggle = screen.getByLabelText(/hide packed/i);
    await user.click(toggle);

    expect(screen.getByText('Passport')).toBeDefined();
    expect(screen.queryByText('Toothbrush')).toBeNull();
  });

  it('shows error message when loading fails', async () => {
    sessionStorage.setItem(
      'packing_list_auth',
      JSON.stringify({
        username: 'testuser',
        token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
      }),
    );

    vi.spyOn(clientModule.apiClient, 'getTrip').mockRejectedValue(
      new Error('Not Found'),
    );

    renderTripPage();

    await waitFor(() => {
      expect(screen.getAllByText('Not Found').length).toBeGreaterThan(0);
    });
  });

  it('displays quantity badge for items with quantity greater than 1', async () => {
    sessionStorage.setItem(
      'packing_list_auth',
      JSON.stringify({
        username: 'testuser',
        token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
      }),
    );

    vi.spyOn(clientModule.apiClient, 'getTrip').mockResolvedValue({
      trip: {
        trip_id: 'trip-001',
        name: 'Test Trip',
        destination: 'Destination',
        departure_date: '2025-03-15',
        return_date: '2025-03-29',
        items: [
          {
            name: 'Socks',
            category: 'clothes',
            quantity: 5,
            tags: [],
            status: 'unpacked',
          },
        ],
        created_at: 1735000000,
        updated_at: 1735000000,
      },
    });

    renderTripPage();

    await waitFor(() => {
      expect(screen.getByText('Socks')).toBeDefined();
    });
    expect(screen.getByText('×5')).toBeDefined();
  });

  it('updates item status and calls updateTrip API with debounce', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });

    sessionStorage.setItem(
      'packing_list_auth',
      JSON.stringify({
        username: 'testuser',
        token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
      }),
    );

    const mockTrip = {
      trip_id: 'trip-001',
      name: 'Test Trip',
      destination: 'Destination',
      departure_date: '2025-03-15',
      return_date: '2025-03-29',
      items: [
        {
          name: 'Passport',
          category: 'travel',
          quantity: 1,
          tags: [],
          status: 'unpacked' as const,
        },
      ],
      created_at: 1735000000,
      updated_at: 1735000000,
    };

    vi.spyOn(clientModule.apiClient, 'getTrip').mockResolvedValue({
      trip: mockTrip,
    });

    const updateTripSpy = vi
      .spyOn(clientModule.apiClient, 'updateTrip')
      .mockResolvedValue({
        trip: {
          ...mockTrip,
          items: [{ ...mockTrip.items[0], status: 'packed' }],
        },
      });

    renderTripPage();

    await waitFor(() => {
      expect(screen.getByText('Passport')).toBeDefined();
    });

    const packedOption = screen.getByRole('radio', { name: 'Packed' });
    await user.click(packedOption);

    expect(updateTripSpy).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(600);

    expect(updateTripSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        trip_id: 'trip-001',
        items: expect.arrayContaining([
          expect.objectContaining({
            name: 'Passport',
            status: 'packed',
          }),
        ]),
      }),
    );

    vi.useRealTimers();
  });

  it('can remove an item from the trip', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });

    sessionStorage.setItem(
      'packing_list_auth',
      JSON.stringify({
        username: 'testuser',
        token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
      }),
    );

    const mockTrip = {
      trip_id: 'trip-001',
      name: 'Test Trip',
      destination: 'Destination',
      departure_date: '2025-03-15',
      return_date: '2025-03-29',
      items: [
        {
          name: 'Passport',
          category: 'travel',
          quantity: 1,
          tags: [],
          status: 'unpacked' as const,
        },
        {
          name: 'Toothbrush',
          category: 'toiletries',
          quantity: 1,
          tags: [],
          status: 'unpacked' as const,
        },
      ],
      created_at: 1735000000,
      updated_at: 1735000000,
    };

    vi.spyOn(clientModule.apiClient, 'getTrip').mockResolvedValue({
      trip: mockTrip,
    });

    const updateTripSpy = vi
      .spyOn(clientModule.apiClient, 'updateTrip')
      .mockResolvedValue({
        trip: {
          ...mockTrip,
          items: mockTrip.items.filter((item) => item.name !== 'Passport'),
        },
      });

    renderTripPage();

    await waitFor(() => {
      expect(screen.getByText('Passport')).toBeDefined();
    });
    expect(screen.getByText('Toothbrush')).toBeDefined();

    const removeButton = screen.getByLabelText('Remove Passport');
    await user.click(removeButton);

    await waitFor(() => {
      expect(screen.queryByText('Passport')).toBeNull();
    });
    expect(screen.getByText('Toothbrush')).toBeDefined();

    await vi.advanceTimersByTimeAsync(600);

    expect(updateTripSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        trip_id: 'trip-001',
        items: expect.not.arrayContaining([
          expect.objectContaining({ name: 'Passport' }),
        ]),
      }),
    );

    vi.useRealTimers();
  });

  it('shows add item button and can add a new item', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });

    sessionStorage.setItem(
      'packing_list_auth',
      JSON.stringify({
        username: 'testuser',
        token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
      }),
    );

    const mockTrip = {
      trip_id: 'trip-001',
      name: 'Test Trip',
      destination: 'Destination',
      departure_date: '2025-03-15',
      return_date: '2025-03-29',
      items: [
        {
          name: 'Passport',
          category: 'travel',
          quantity: 1,
          tags: [],
          status: 'unpacked' as const,
        },
      ],
      created_at: 1735000000,
      updated_at: 1735000000,
    };

    vi.spyOn(clientModule.apiClient, 'getTrip').mockResolvedValue({
      trip: mockTrip,
    });

    const updateTripSpy = vi
      .spyOn(clientModule.apiClient, 'updateTrip')
      .mockResolvedValue({
        trip: {
          ...mockTrip,
          items: [
            ...mockTrip.items,
            {
              name: 'Sunglasses',
              category: 'accessories',
              quantity: 1,
              tags: [],
              status: 'unpacked' as const,
            },
          ],
        },
      });

    renderTripPage();

    await waitFor(() => {
      expect(screen.getByText('Passport')).toBeDefined();
    });

    const addButton = screen.getByRole('button', { name: 'Add item' });
    await user.click(addButton);

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeDefined();
    });

    const nameInput = screen.getByRole('textbox', { name: 'Name' });
    await user.type(nameInput, 'Sunglasses');

    const categoryInput = screen.getByRole('textbox', { name: 'Category' });
    await user.type(categoryInput, 'accessories');

    const submitButton = screen.getByRole('button', { name: 'Add' });
    await user.click(submitButton);

    await waitFor(() => {
      expect(screen.getByText('Sunglasses')).toBeDefined();
    });

    await vi.advanceTimersByTimeAsync(600);

    expect(updateTripSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        trip_id: 'trip-001',
        items: expect.arrayContaining([
          expect.objectContaining({ name: 'Sunglasses' }),
        ]),
      }),
    );

    vi.useRealTimers();
  });

  it('can edit an existing item', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });

    sessionStorage.setItem(
      'packing_list_auth',
      JSON.stringify({
        username: 'testuser',
        token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
      }),
    );

    const mockTrip = {
      trip_id: 'trip-001',
      name: 'Test Trip',
      destination: 'Destination',
      departure_date: '2025-03-15',
      return_date: '2025-03-29',
      items: [
        {
          name: 'Passport',
          category: 'travel',
          quantity: 1,
          tags: [],
          status: 'unpacked' as const,
        },
      ],
      created_at: 1735000000,
      updated_at: 1735000000,
    };

    vi.spyOn(clientModule.apiClient, 'getTrip').mockResolvedValue({
      trip: mockTrip,
    });

    const updateTripSpy = vi
      .spyOn(clientModule.apiClient, 'updateTrip')
      .mockResolvedValue({
        trip: {
          ...mockTrip,
          items: [{ ...mockTrip.items[0], quantity: 2 }],
        },
      });

    renderTripPage();

    await waitFor(() => {
      expect(screen.getByText('Passport')).toBeDefined();
    });

    const editButton = screen.getByLabelText('Edit Passport');
    await user.click(editButton);

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeDefined();
    });

    const quantityInput = screen.getByLabelText('Quantity');
    await user.clear(quantityInput);
    await user.type(quantityInput, '2');

    const saveButton = screen.getByRole('button', { name: 'Save' });
    await user.click(saveButton);

    await waitFor(() => {
      expect(screen.getByText('×2')).toBeDefined();
    });

    await vi.advanceTimersByTimeAsync(600);

    expect(updateTripSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        trip_id: 'trip-001',
        items: expect.arrayContaining([
          expect.objectContaining({
            name: 'Passport',
            quantity: 2,
          }),
        ]),
      }),
    );

    vi.useRealTimers();
  });

  it('sorts items alphabetically within each category', async () => {
    sessionStorage.setItem(
      'packing_list_auth',
      JSON.stringify({
        username: 'testuser',
        token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
      }),
    );

    vi.spyOn(clientModule.apiClient, 'getTrip').mockResolvedValue({
      trip: {
        trip_id: 'trip-001',
        name: 'Test Trip',
        destination: 'Destination',
        departure_date: '2025-03-15',
        return_date: '2025-03-29',
        items: [
          {
            name: 'Zebra hat',
            category: 'clothes',
            quantity: 1,
            tags: [],
            status: 'unpacked',
          },
          {
            name: 'Apple watch',
            category: 'clothes',
            quantity: 1,
            tags: [],
            status: 'unpacked',
          },
          {
            name: 'Mittens',
            category: 'clothes',
            quantity: 1,
            tags: [],
            status: 'unpacked',
          },
        ],
        created_at: 1735000000,
        updated_at: 1735000000,
      },
    });

    renderTripPage();

    await waitFor(() => {
      expect(screen.getByText('Zebra hat')).toBeDefined();
    });

    const itemTexts = screen
      .getAllByText(/Zebra hat|Apple watch|Mittens/)
      .map((el) => el.textContent);
    expect(itemTexts).toEqual(['Apple watch', 'Mittens', 'Zebra hat']);
  });

  it('can edit trip details via the edit trip modal', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });

    sessionStorage.setItem(
      'packing_list_auth',
      JSON.stringify({
        username: 'testuser',
        token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
      }),
    );

    const mockTrip = {
      trip_id: 'trip-001',
      name: 'Test Trip',
      destination: 'Tokyo',
      departure_date: '2025-03-15',
      return_date: '2025-03-29',
      items: [
        {
          name: 'Passport',
          category: 'travel',
          quantity: 1,
          tags: [],
          status: 'unpacked' as const,
        },
      ],
      created_at: 1735000000,
      updated_at: 1735000000,
    };

    vi.spyOn(clientModule.apiClient, 'getTrip').mockResolvedValue({
      trip: mockTrip,
    });

    const updateTripSpy = vi
      .spyOn(clientModule.apiClient, 'updateTrip')
      .mockResolvedValue({
        trip: {
          ...mockTrip,
          name: 'Updated Trip Name',
          destination: 'Osaka',
        },
      });

    renderTripPage();

    await waitFor(() => {
      expect(screen.getByText('Test Trip')).toBeDefined();
    });

    const editTripButton = screen.getByLabelText('Edit trip details');
    await user.click(editTripButton);

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeDefined();
    });
    expect(screen.getByText('Edit trip details')).toBeDefined();

    const nameInput = screen.getByRole('textbox', { name: 'Name' });
    await user.clear(nameInput);
    await user.type(nameInput, 'Updated Trip Name');

    const destinationInput = screen.getByRole('textbox', {
      name: 'Destination',
    });
    await user.clear(destinationInput);
    await user.type(destinationInput, 'Osaka');

    const saveButton = screen.getByRole('button', { name: 'Save' });
    await user.click(saveButton);

    await waitFor(() => {
      expect(screen.getByText('Updated Trip Name')).toBeDefined();
    });
    expect(screen.getByText('Osaka')).toBeDefined();

    await vi.advanceTimersByTimeAsync(600);

    expect(updateTripSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        trip_id: 'trip-001',
        name: 'Updated Trip Name',
        destination: 'Osaka',
        departure_date: '2025-03-15',
        return_date: '2025-03-29',
        items: expect.arrayContaining([
          expect.objectContaining({ name: 'Passport' }),
        ]),
      }),
    );

    vi.useRealTimers();
  });
});
