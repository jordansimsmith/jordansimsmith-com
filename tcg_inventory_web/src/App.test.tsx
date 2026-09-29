import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { MemoryRouter, Routes, Route, Navigate } from 'react-router-dom';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { App } from './App';
import { LoginPage } from './pages/LoginPage';
import { InventoryPage } from './pages/InventoryPage';
import { ScanPage } from './pages/ScanPage';
import { ScanDetailPage } from './pages/ScanDetailPage';
import { getSession } from './auth/session';
import { apiClient } from './api/client';
import { GamesProvider } from './GamesProvider';

const REGISTERED_GAMES = [
  {
    id: 'mtg',
    display_name: 'Magic: The Gathering',
    scanning_enabled: true,
    csv_import_enabled: true,
    finishes: [
      { id: 'normal', display_name: 'Normal' },
      { id: 'foil', display_name: 'Foil' },
      { id: 'etched', display_name: 'Etched' },
    ],
  },
];

function RequireAuth({ children }: { children: React.ReactNode }) {
  const session = getSession();
  if (!session) {
    return <Navigate to="/" replace />;
  }
  return (
    <GamesProvider initialGames={REGISTERED_GAMES}>{children}</GamesProvider>
  );
}

function HomeRoute() {
  const session = getSession();
  if (session) {
    return <Navigate to="/inventory" replace />;
  }
  return <LoginPage />;
}

function renderApp(initialRoute = '/') {
  return render(
    <MantineProvider>
      <MemoryRouter initialEntries={[initialRoute]}>
        <Routes>
          <Route path="/" element={<HomeRoute />} />
          <Route
            path="/inventory"
            element={
              <RequireAuth>
                <InventoryPage />
              </RequireAuth>
            }
          />
          <Route
            path="/scans"
            element={
              <RequireAuth>
                <ScanPage />
              </RequireAuth>
            }
          />
          <Route
            path="/scans/:scanId"
            element={
              <RequireAuth>
                <ScanDetailPage />
              </RequireAuth>
            }
          />
        </Routes>
      </MemoryRouter>
    </MantineProvider>,
  );
}

function renderActualApp(initialPath: string) {
  window.history.pushState({}, '', initialPath);
  return render(
    <MantineProvider>
      <App />
    </MantineProvider>,
  );
}

function setAuth() {
  localStorage.setItem(
    'tcg_inventory_auth',
    JSON.stringify({
      username: 'testuser',
      token: btoa('testuser:testpass'),
    }),
  );
}

describe('App', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    window.history.pushState({}, '', '/');
  });

  it('renders login page when not authenticated', () => {
    renderApp();

    expect(
      screen.getByRole('heading', { name: /tcg inventory/i }),
    ).toBeDefined();
    expect(screen.getByLabelText(/username/i)).toBeDefined();
  });

  it('redirects authenticated users from / to /inventory', () => {
    setAuth();
    renderApp('/');

    expect(
      screen.getByRole('heading', { level: 1, name: /inventory/i }),
    ).toBeDefined();
  });

  it('redirects unauthenticated users to login from protected routes', () => {
    renderApp('/inventory');

    expect(
      screen.getByRole('heading', { name: /tcg inventory/i }),
    ).toBeDefined();
    expect(screen.getByLabelText(/username/i)).toBeDefined();
  });

  it('protects the scan route', () => {
    renderApp('/scans');

    expect(
      screen.getByRole('heading', { name: /tcg inventory/i }),
    ).toBeDefined();
    expect(screen.getByLabelText(/username/i)).toBeDefined();
  });

  it('protects the scan detail route', () => {
    renderApp('/scans/fake-scan-identifying');

    expect(
      screen.getByRole('heading', { name: /tcg inventory/i }),
    ).toBeDefined();
    expect(screen.getByLabelText(/username/i)).toBeDefined();
  });

  it('clears session and redirects to login on logout', async () => {
    const user = userEvent.setup();
    setAuth();
    renderApp('/inventory');

    expect(
      screen.getByRole('heading', { level: 1, name: /inventory/i }),
    ).toBeDefined();

    await user.click(screen.getByRole('button', { name: /log out/i }));

    await waitFor(() => {
      expect(screen.getByLabelText(/username/i)).toBeDefined();
    });
    expect(localStorage.getItem('tcg_inventory_auth')).toBeNull();
  });

  it('loads game metadata once across authenticated routes and after reload', async () => {
    const user = userEvent.setup();
    const getGames = vi.spyOn(apiClient, 'getGames');
    setAuth();
    const workspace = renderActualApp('/inventory');

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Inventory' }),
    ).toBeDefined();
    expect(getGames).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('link', { name: 'Scans' }));
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Scans' }),
    ).toBeDefined();
    expect(getGames).toHaveBeenCalledTimes(1);

    workspace.unmount();
    renderActualApp('/inventory');
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Inventory' }),
    ).toBeDefined();
    expect(getGames).toHaveBeenCalledTimes(2);
  });
});
