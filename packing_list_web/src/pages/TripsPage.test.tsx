import { render, screen, waitFor, cleanup } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { Notifications, notifications } from '@mantine/notifications';
import { MemoryRouter, Routes, Route, Navigate } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { TripsPage } from './TripsPage';
import { getSession } from '../auth/session';
import * as clientModule from '../api/client';

function RequireAuth({ children }: { children: React.ReactNode }) {
  const session = getSession();
  if (!session) {
    return <Navigate to="/" replace />;
  }
  return <>{children}</>;
}

function renderTripsPage() {
  return render(
    <MantineProvider>
      <Notifications />
      <MemoryRouter initialEntries={['/trips']}>
        <Routes>
          <Route path="/" element={<div>Login page</div>} />
          <Route
            path="/trips"
            element={
              <RequireAuth>
                <TripsPage />
              </RequireAuth>
            }
          />
        </Routes>
      </MemoryRouter>
    </MantineProvider>,
  );
}

describe('TripsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    notifications.clean();
  });

  afterEach(() => {
    cleanup();
    notifications.clean();
  });

  it('redirects to login when not authenticated', async () => {
    vi.spyOn(clientModule.apiClient, 'getTrips').mockResolvedValue({
      trips: [],
    });
    renderTripsPage();

    await waitFor(() => {
      expect(screen.getByText('Login page')).toBeDefined();
    });
  });

  it('displays trips when loaded', async () => {
    localStorage.setItem(
      'packing_list_auth',
      JSON.stringify({
        username: 'testuser',
        token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
      }),
    );

    vi.spyOn(clientModule.apiClient, 'getTrips').mockResolvedValue({
      trips: [
        {
          trip_id: 'trip-001',
          name: 'Japan 2025',
          destination: 'Tokyo',
          departure_date: '2025-03-15',
          return_date: '2025-03-29',
          created_at: 1735000000,
          updated_at: 1735000000,
        },
        {
          trip_id: 'trip-002',
          name: 'Ski trip',
          destination: 'Queenstown',
          departure_date: '2025-07-10',
          return_date: '2025-07-17',
          created_at: 1735100000,
          updated_at: 1735100000,
        },
      ],
    });

    renderTripsPage();

    await waitFor(() => {
      expect(screen.getByText('Japan 2025')).toBeDefined();
    });
    expect(screen.getByText('Tokyo')).toBeDefined();
    expect(screen.getByText('Ski trip')).toBeDefined();
    expect(screen.getByText('Queenstown')).toBeDefined();
  });

  it('shows empty state when no trips exist', async () => {
    localStorage.setItem(
      'packing_list_auth',
      JSON.stringify({
        username: 'testuser',
        token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
      }),
    );

    vi.spyOn(clientModule.apiClient, 'getTrips').mockResolvedValue({
      trips: [],
    });

    renderTripsPage();

    await waitFor(() => {
      expect(screen.getByText(/no trips yet/i)).toBeDefined();
    });
  });

  it('shows error message when loading fails', async () => {
    localStorage.setItem(
      'packing_list_auth',
      JSON.stringify({
        username: 'testuser',
        token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
      }),
    );

    vi.spyOn(clientModule.apiClient, 'getTrips').mockRejectedValue(
      new Error('Network error'),
    );

    renderTripsPage();

    await waitFor(() => {
      expect(screen.getByText('Network error')).toBeDefined();
    });
  });
});
