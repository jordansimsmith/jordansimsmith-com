import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { DatesProvider } from '@mantine/dates';
import { Notifications, notifications } from '@mantine/notifications';
import { MemoryRouter, Routes, Route, Navigate } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { CreateTripPage } from './CreateTripPage';
import { getSession } from '../auth/session';
import * as clientModule from '../api/client';

function RequireAuth({ children }: { children: React.ReactNode }) {
  const session = getSession();
  if (!session) {
    return <Navigate to="/" replace />;
  }
  return <>{children}</>;
}

function renderCreateTripPage() {
  return render(
    <MantineProvider>
      <DatesProvider settings={{}}>
        <Notifications />
        <MemoryRouter initialEntries={['/trips/create']}>
          <Routes>
            <Route path="/" element={<div>Login page</div>} />
            <Route path="/trips" element={<div>Trips page</div>} />
            <Route
              path="/trips/create"
              element={
                <RequireAuth>
                  <CreateTripPage />
                </RequireAuth>
              }
            />
            <Route
              path="/trips/:tripId"
              element={<div>Trip detail page</div>}
            />
          </Routes>
        </MemoryRouter>
      </DatesProvider>
    </MantineProvider>,
  );
}

const mockTemplatesResponse: clientModule.TemplatesResponse = {
  base_template: {
    base_template_id: 'generic',
    name: 'generic',
    items: [
      {
        name: 'passport',
        category: 'travel',
        quantity: 1,
        tags: ['hand luggage'],
      },
      {
        name: 'phone charger',
        category: 'electronics',
        quantity: 1,
        tags: [],
      },
    ],
  },
  variations: [
    {
      variation_id: 'skiing',
      name: 'skiing',
      items: [
        {
          name: 'ski jacket',
          category: 'clothes',
          quantity: 1,
          tags: [],
        },
      ],
    },
  ],
};

describe('CreateTripPage', () => {
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
    vi.spyOn(clientModule.apiClient, 'getTemplates').mockResolvedValue(
      mockTemplatesResponse,
    );
    renderCreateTripPage();

    await waitFor(() => {
      expect(screen.getByText('Login page')).toBeDefined();
    });
  });

  it('displays create trip form when authenticated', async () => {
    sessionStorage.setItem(
      'packing_list_auth',
      JSON.stringify({
        username: 'testuser',
        token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
      }),
    );

    vi.spyOn(clientModule.apiClient, 'getTemplates').mockResolvedValue(
      mockTemplatesResponse,
    );

    renderCreateTripPage();

    await waitFor(() => {
      expect(screen.getByLabelText('Name')).toBeDefined();
    });
    expect(screen.getByRole('heading', { name: 'Create trip' })).toBeDefined();
    expect(screen.getByLabelText('Destination')).toBeDefined();
    expect(screen.getByLabelText('Departure date')).toBeDefined();
    expect(screen.getByLabelText('Return date')).toBeDefined();
  });

  it('displays base template items in preview', async () => {
    sessionStorage.setItem(
      'packing_list_auth',
      JSON.stringify({
        username: 'testuser',
        token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
      }),
    );

    vi.spyOn(clientModule.apiClient, 'getTemplates').mockResolvedValue(
      mockTemplatesResponse,
    );

    renderCreateTripPage();

    await waitFor(() => {
      expect(screen.getByText('passport')).toBeDefined();
    });
    expect(screen.getByText('phone charger')).toBeDefined();
    expect(screen.getByText('2 items')).toBeDefined();
  });

  it('displays available variations', async () => {
    sessionStorage.setItem(
      'packing_list_auth',
      JSON.stringify({
        username: 'testuser',
        token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
      }),
    );

    vi.spyOn(clientModule.apiClient, 'getTemplates').mockResolvedValue(
      mockTemplatesResponse,
    );

    renderCreateTripPage();

    await waitFor(() => {
      expect(screen.getByText('skiing')).toBeDefined();
    });
    expect(screen.getByText('1 items')).toBeDefined();
  });

  it('adds variation items when variation is added', async () => {
    sessionStorage.setItem(
      'packing_list_auth',
      JSON.stringify({
        username: 'testuser',
        token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
      }),
    );

    vi.spyOn(clientModule.apiClient, 'getTemplates').mockResolvedValue(
      mockTemplatesResponse,
    );

    const user = userEvent.setup();
    renderCreateTripPage();

    await waitFor(() => {
      expect(screen.getByText('skiing')).toBeDefined();
    });

    await user.click(screen.getByText('skiing'));

    await waitFor(() => {
      expect(screen.getByText('ski jacket')).toBeDefined();
    });

    await user.click(screen.getByText('Apply variation'));

    await waitFor(() => {
      expect(screen.getByText('3 items')).toBeDefined();
    });
    expect(screen.getByText('Added')).toBeDefined();
  });

  it('can remove items from preview', async () => {
    sessionStorage.setItem(
      'packing_list_auth',
      JSON.stringify({
        username: 'testuser',
        token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
      }),
    );

    vi.spyOn(clientModule.apiClient, 'getTemplates').mockResolvedValue(
      mockTemplatesResponse,
    );

    const user = userEvent.setup();
    renderCreateTripPage();

    await waitFor(() => {
      expect(screen.getByText('passport')).toBeDefined();
    });

    await user.click(screen.getByLabelText('Remove passport'));

    await waitFor(() => {
      expect(screen.queryByText('passport')).toBeNull();
    });
    expect(screen.getByText('phone charger')).toBeDefined();
  });

  it('shows add item button', async () => {
    sessionStorage.setItem(
      'packing_list_auth',
      JSON.stringify({
        username: 'testuser',
        token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
      }),
    );

    vi.spyOn(clientModule.apiClient, 'getTemplates').mockResolvedValue(
      mockTemplatesResponse,
    );

    renderCreateTripPage();

    await waitFor(() => {
      expect(screen.getByText('passport')).toBeDefined();
    });

    expect(screen.getByRole('button', { name: 'Add item' })).toBeDefined();
  });
});
