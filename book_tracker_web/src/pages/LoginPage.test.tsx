import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { Notifications, notifications } from '@mantine/notifications';
import { MemoryRouter, Routes, Route, Navigate } from 'react-router-dom';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { LoginPage } from './LoginPage';
import { BooksPage } from './BooksPage';
import { getSession } from '../auth/session';

function RequireAuth({ children }: { children: React.ReactNode }) {
  const session = getSession();
  if (!session) {
    return <Navigate to="/" replace />;
  }
  return <>{children}</>;
}

function renderApp(initialEntry: string) {
  return render(
    <MantineProvider>
      <Notifications />
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/" element={<LoginPage />} />
          <Route
            path="/books"
            element={
              <RequireAuth>
                <BooksPage />
              </RequireAuth>
            }
          />
        </Routes>
      </MemoryRouter>
    </MantineProvider>,
  );
}

describe('LoginPage', () => {
  beforeEach(() => {
    localStorage.clear();
    notifications.clean();
  });

  afterEach(() => {
    cleanup();
    notifications.clean();
  });

  it('renders the login form', () => {
    renderApp('/');

    expect(
      screen.getByRole('heading', { name: /book tracker/i }),
    ).toBeDefined();
    expect(screen.getByLabelText(/username/i)).toBeDefined();
    expect(screen.getByLabelText(/password/i)).toBeDefined();
    expect(screen.getByRole('button', { name: /log in/i })).toBeDefined();
  });

  it('shows validation errors when fields are empty', async () => {
    const user = userEvent.setup();
    renderApp('/');

    await user.click(screen.getByRole('button', { name: /log in/i }));

    await waitFor(() => {
      expect(screen.getByText(/username is required/i)).toBeDefined();
    });
    expect(screen.getByText(/password is required/i)).toBeDefined();
  });

  it('persists the session and navigates to /books on submit', async () => {
    const user = userEvent.setup();
    renderApp('/');

    await user.type(screen.getByLabelText(/username/i), 'alice');
    await user.type(screen.getByLabelText(/password/i), 'secret');
    await user.click(screen.getByRole('button', { name: /log in/i }));

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /^books$/i })).toBeDefined();
    });

    const session = getSession();
    expect(session?.username).toBe('alice');
    expect(session?.token).toBe(btoa('alice:secret'));
  });

  it('redirects unauthenticated visits to /books back to /', async () => {
    renderApp('/books');

    await waitFor(() => {
      expect(
        screen.getByRole('heading', { name: /book tracker/i }),
      ).toBeDefined();
    });
    expect(getSession()).toBeNull();
  });

  it('clears the session and returns to / when logging out', async () => {
    localStorage.setItem(
      'book_tracker_auth',
      JSON.stringify({ username: 'alice', token: btoa('alice:secret') }),
    );

    const user = userEvent.setup();
    renderApp('/books');

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /^books$/i })).toBeDefined();
    });

    await user.click(screen.getByRole('button', { name: /log out/i }));

    await waitFor(() => {
      expect(
        screen.getByRole('heading', { name: /book tracker/i }),
      ).toBeDefined();
    });
    expect(getSession()).toBeNull();
  });
});
