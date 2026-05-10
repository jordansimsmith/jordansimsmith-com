import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { Notifications, notifications } from '@mantine/notifications';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { LoginPage } from './LoginPage';
import { getSession } from '../auth/session';
import * as clientModule from '../api/client';

function renderLoginPage() {
  return render(
    <MantineProvider>
      <Notifications />
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<LoginPage />} />
          <Route path="/search" element={<div>Search page</div>} />
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
    vi.restoreAllMocks();
  });

  it('renders login form', () => {
    renderLoginPage();

    expect(
      screen.getByRole('heading', { name: /japanese dictionary/i }),
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

  it('validates credentials with an empty search and navigates on success', async () => {
    const searchSpy = vi
      .spyOn(clientModule.apiClient, 'search')
      .mockResolvedValue({ results: [] });

    const user = userEvent.setup();
    renderLoginPage();

    await user.type(screen.getByLabelText(/username/i), 'alice');
    await user.type(screen.getByLabelText(/password/i), 'hunter2');
    await user.click(screen.getByRole('button', { name: /log in/i }));

    await waitFor(() => {
      expect(screen.getByText('Search page')).toBeDefined();
    });
    expect(searchSpy).toHaveBeenCalledWith('');
    expect(getSession()).toEqual({
      username: 'alice',
      token: btoa('alice:hunter2'),
    });
  });

  it('clears the session and shows an error notification on failed validation', async () => {
    vi.spyOn(clientModule.apiClient, 'search').mockRejectedValue(
      new Error('Unauthorized'),
    );

    const user = userEvent.setup();
    renderLoginPage();

    await user.type(screen.getByLabelText(/username/i), 'alice');
    await user.type(screen.getByLabelText(/password/i), 'wrongpass');
    await user.click(screen.getByRole('button', { name: /log in/i }));

    await waitFor(() => {
      expect(screen.getByText(/login failed/i)).toBeDefined();
    });
    expect(getSession()).toBeNull();
  });
});
