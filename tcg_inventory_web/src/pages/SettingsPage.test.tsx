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
import { SettingsPage } from './SettingsPage';
import * as clientModule from '../api/client';

function renderSettingsPage() {
  return render(
    <MantineProvider>
      <Notifications />
      <MemoryRouter initialEntries={['/settings']}>
        <Routes>
          <Route path="/settings" element={<SettingsPage />} />
        </Routes>
      </MemoryRouter>
    </MantineProvider>,
  );
}

function tokenInput(): HTMLInputElement {
  return screen.getByLabelText('FetchTCG refresh token') as HTMLInputElement;
}

describe('SettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  it('shows an empty masked field when no credential is set', async () => {
    vi.spyOn(clientModule.apiClient, 'getSettings').mockResolvedValue({
      credential_set: false,
      updated_at: null,
      track_orders_after: null,
    });

    renderSettingsPage();

    expect(
      await screen.findByPlaceholderText('Enter refresh token'),
    ).toBeDefined();
    expect(tokenInput().value).toBe('');
    expect(tokenInput().type).toBe('password');
    expect(screen.queryByText(/Last updated/)).toBeNull();
    const connection = screen.getByRole('region', {
      name: 'FetchTCG connection',
    });
    expect(within(connection).queryByText('No token saved')).toBeNull();
    expect(screen.queryByText(/write-only and never shown/)).toBeNull();
    expect(
      within(connection).getByRole('button', { name: 'Save token' }),
    ).toBeDefined();
  });

  it('shows a masked placeholder and last-updated when a credential is set', async () => {
    vi.spyOn(clientModule.apiClient, 'getSettings').mockResolvedValue({
      credential_set: true,
      updated_at: 1765420800,
      track_orders_after: null,
    });

    renderSettingsPage();

    expect(
      await screen.findByPlaceholderText('••••••••••••••••'),
    ).toBeDefined();
    expect(
      screen.getByText(
        `Last updated ${new Date(1765420800 * 1000).toLocaleString()}`,
      ),
    ).toBeDefined();
    expect(tokenInput().value).toBe('');
    expect(screen.queryByText('Token saved')).toBeNull();
  });

  it('saves a credential write-only and clears the field', async () => {
    const user = userEvent.setup();
    vi.spyOn(clientModule.apiClient, 'getSettings').mockResolvedValue({
      credential_set: false,
      updated_at: null,
      track_orders_after: null,
    });
    const updateSettingsMock = vi
      .spyOn(clientModule.apiClient, 'updateSettings')
      .mockResolvedValue({
        credential_set: true,
        updated_at: 1765507200,
        track_orders_after: null,
      });

    renderSettingsPage();
    await screen.findByPlaceholderText('Enter refresh token');

    await user.type(tokenInput(), 'new-refresh-token');
    await user.click(screen.getByRole('button', { name: 'Save token' }));

    expect(updateSettingsMock).toHaveBeenCalledWith({
      refresh_token: 'new-refresh-token',
    });
    await waitFor(() => {
      expect(tokenInput().value).toBe('');
    });
    expect(
      screen.getByText(
        `Last updated ${new Date(1765507200 * 1000).toLocaleString()}`,
      ),
    ).toBeDefined();
    expect(screen.getByPlaceholderText('••••••••••••••••')).toBeDefined();
    expect(screen.queryByDisplayValue('new-refresh-token')).toBeNull();
    expect(screen.queryByText('Token saved')).toBeNull();
  });

  it('shows the track orders after date input', async () => {
    vi.spyOn(clientModule.apiClient, 'getSettings').mockResolvedValue({
      credential_set: true,
      updated_at: 1765420800,
      track_orders_after: null,
    });

    renderSettingsPage();

    expect(await screen.findByPlaceholderText('No cutoff set')).toBeDefined();
    const tracking = screen.getByRole('region', { name: 'Order tracking' });
    expect(within(tracking).queryByText('Current cutoff')).toBeNull();
    expect(
      within(tracking).getByRole('button', { name: 'Save date' }),
    ).toBeDefined();
    expect(
      screen.getByText(/Orders accepted on or after this date are tracked/),
    ).toBeDefined();
  });

  it('shows the saved cutoff without prompting another save', async () => {
    vi.spyOn(clientModule.apiClient, 'getSettings').mockResolvedValue({
      credential_set: true,
      updated_at: 1765420800,
      track_orders_after: Math.floor(new Date(2026, 8, 1).getTime() / 1000),
    });

    renderSettingsPage();

    expect(
      ((await screen.findByLabelText('Track orders after')) as HTMLInputElement)
        .value,
    ).toBe('September 1, 2026');
    expect(screen.queryByText('Current cutoff')).toBeNull();
    expect(screen.getByRole('button', { name: 'Save date' })).toHaveProperty(
      'disabled',
      true,
    );
  });
});
