import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { Notifications, notifications } from '@mantine/notifications';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { LoginPage } from './LoginPage';
import * as sessionModule from '../auth/session';
import * as clientModule from '../api/client';

function renderLoginPage() {
  return render(
    <MantineProvider>
      <Notifications />
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<LoginPage />} />
          <Route path="/inventory" element={<div>Inventory page</div>} />
        </Routes>
      </MemoryRouter>
    </MantineProvider>,
  );
}

describe('LoginPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    notifications.clean();
  });

  afterEach(() => {
    cleanup();
    notifications.clean();
  });

  it('renders login form', () => {
    renderLoginPage();

    expect(
      screen.getByRole('heading', { name: /tcg inventory/i }),
    ).toBeDefined();
    expect(screen.getByLabelText(/username/i)).toBeDefined();
    expect(screen.getByLabelText(/password/i)).toBeDefined();
    expect(screen.getByRole('button', { name: /log in/i })).toBeDefined();
  });

  it('shows validation errors when fields are empty', async () => {
    const user = userEvent.setup();
    renderLoginPage();

    await user.click(screen.getByRole('button', { name: /log in/i }));

    await waitFor(() => {
      expect(screen.getByText(/username is required/i)).toBeDefined();
    });
    expect(screen.getByText(/password is required/i)).toBeDefined();
  });

  it('navigates to inventory page on successful login', async () => {
    vi.spyOn(clientModule.apiClient, 'getSettings').mockResolvedValue({
      credential_set: false,
      updated_at: null,
      track_orders_after: null,
    });

    const user = userEvent.setup();
    renderLoginPage();

    await user.type(screen.getByLabelText(/username/i), 'testuser');
    await user.type(screen.getByLabelText(/password/i), 'testpass');
    await user.click(screen.getByRole('button', { name: /log in/i }));

    await waitFor(() => {
      expect(screen.getByText('Inventory page')).toBeDefined();
    });
    expect(clientModule.apiClient.getSettings).toHaveBeenCalled();
  });

  it('shows error notification and clears session on failed login', async () => {
    vi.spyOn(clientModule.apiClient, 'getSettings').mockRejectedValue(
      new Error('Unauthorized'),
    );
    const clearSessionSpy = vi.spyOn(sessionModule, 'clearSession');

    const user = userEvent.setup();
    renderLoginPage();

    await user.type(screen.getByLabelText(/username/i), 'testuser');
    await user.type(screen.getByLabelText(/password/i), 'wrongpass');
    await user.click(screen.getByRole('button', { name: /log in/i }));

    await waitFor(() => {
      expect(screen.getByText(/login failed/i)).toBeDefined();
    });
    expect(clearSessionSpy).toHaveBeenCalled();
  });
});
