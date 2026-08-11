import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { MemoryRouter, Routes, Route, Navigate } from 'react-router-dom';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { LoginPage } from './pages/LoginPage';
import { InventoryPage } from './pages/InventoryPage';
import { getSession } from './auth/session';

function RequireAuth({ children }: { children: React.ReactNode }) {
  const session = getSession();
  if (!session) {
    return <Navigate to="/" replace />;
  }
  return <>{children}</>;
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
        </Routes>
      </MemoryRouter>
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
      screen.getByRole('heading', { level: 2, name: /inventory/i }),
    ).toBeDefined();
  });

  it('redirects unauthenticated users to login from protected routes', () => {
    renderApp('/inventory');

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
      screen.getByRole('heading', { level: 2, name: /inventory/i }),
    ).toBeDefined();

    await user.click(screen.getByRole('button', { name: /log out/i }));

    await waitFor(() => {
      expect(screen.getByLabelText(/username/i)).toBeDefined();
    });
    expect(localStorage.getItem('tcg_inventory_auth')).toBeNull();
  });
});
