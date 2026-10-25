import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { Notifications, notifications } from '@mantine/notifications';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SettingsPage } from './SettingsPage';
import * as clientModule from '../api/client';

const defaultSettings = {
  credential_set: true,
  updated_at: 1765420800,
  track_orders_after: null,
};
const savedAccount = {
  seller_username: 'verified-seller',
  credentials_updated_at: 1765507200,
};

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
    vi.spyOn(clientModule.apiClient, 'getSettings').mockResolvedValue({
      ...defaultSettings,
    });
    vi.spyOn(clientModule.apiClient, 'getFetchTcgAccount').mockResolvedValue(
      null,
    );
    vi.spyOn(clientModule.apiClient, 'updateSettings').mockResolvedValue({
      ...defaultSettings,
    });
    vi.spyOn(
      clientModule.apiClient,
      'updateFetchTcgCredentials',
    ).mockResolvedValue({ ...savedAccount });
    notifications.clean();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    notifications.clean();
  });

  it('shows connection setup when account metadata is absent, regardless of legacy credential settings', async () => {
    renderSettingsPage();

    const connection = screen.getByRole('region', {
      name: 'FetchTCG connection',
    });
    expect(
      await within(connection).findByText(
        'Save or re-save your FetchTCG refresh token to verify your seller account.',
      ),
    ).toBeDefined();
    expect(
      within(connection).getByPlaceholderText('Enter refresh token'),
    ).toBeDefined();
    expect(tokenInput().type).toBe('password');
    expect(
      within(connection).queryByLabelText('FetchTCG seller username'),
    ).toBeNull();
    expect(clientModule.apiClient.getFetchTcgAccount).toHaveBeenCalledTimes(1);
    expect(clientModule.apiClient.getSettings).toHaveBeenCalledTimes(1);
  });

  it('shows the exact seller username read-only and formats the saved time', async () => {
    vi.spyOn(clientModule.apiClient, 'getFetchTcgAccount').mockResolvedValue({
      seller_username: ' Seller Name ',
      credentials_updated_at: 1765507200,
    });

    renderSettingsPage();

    const username = (await screen.findByLabelText(
      'FetchTCG seller username',
    )) as HTMLInputElement;
    expect(username.value).toBe(' Seller Name ');
    expect(username.readOnly).toBe(true);
    expect(
      screen.getByText(
        `Last updated ${new Date(1765507200 * 1000).toLocaleString()}`,
      ),
    ).toBeDefined();
    expect(tokenInput().value).toBe('');
    expect(tokenInput().placeholder).toBe('••••••••••••••••');
  });

  it('saves credentials through FetchTCG, preserves the token, and uses the response metadata', async () => {
    const user = userEvent.setup();
    renderSettingsPage();
    await screen.findByPlaceholderText('Enter refresh token');

    await user.type(tokenInput(), ' token with spaces ');
    await user.click(screen.getByRole('button', { name: 'Save token' }));

    expect(
      clientModule.apiClient.updateFetchTcgCredentials,
    ).toHaveBeenCalledWith({ refresh_token: ' token with spaces ' });
    expect(clientModule.apiClient.updateSettings).not.toHaveBeenCalled();
    await waitFor(() => expect(tokenInput().value).toBe(''));
    expect(
      (screen.getByLabelText('FetchTCG seller username') as HTMLInputElement)
        .value,
    ).toBe('verified-seller');
    expect(
      screen.getByText(
        `Last updated ${new Date(savedAccount.credentials_updated_at * 1000).toLocaleString()}`,
      ),
    ).toBeDefined();
    expect(screen.queryByDisplayValue(' token with spaces ')).toBeNull();
    expect(screen.getByText('FetchTCG refresh token updated')).toBeDefined();
  });

  it('keeps the candidate and saved account when credential validation fails', async () => {
    const user = userEvent.setup();
    vi.spyOn(clientModule.apiClient, 'getFetchTcgAccount').mockResolvedValue({
      ...savedAccount,
    });
    vi.spyOn(
      clientModule.apiClient,
      'updateFetchTcgCredentials',
    ).mockRejectedValue(
      new Error('The refresh token was rejected. Check it and try again.'),
    );
    renderSettingsPage();
    await screen.findByLabelText('FetchTCG seller username');

    await user.type(tokenInput(), 'candidate-token');
    await user.click(screen.getByRole('button', { name: 'Save token' }));

    expect(
      await screen.findByText(
        'The refresh token was rejected. Check it and try again.',
      ),
    ).toBeDefined();
    expect(tokenInput().value).toBe('candidate-token');
    expect(
      (screen.getByLabelText('FetchTCG seller username') as HTMLInputElement)
        .value,
    ).toBe(savedAccount.seller_username);
    expect(clientModule.apiClient.updateSettings).not.toHaveBeenCalled();
  });

  it('replaces the displayed account only after credential verification succeeds', async () => {
    const user = userEvent.setup();
    const replacement = {
      seller_username: 'replacement-seller',
      credentials_updated_at: 1765593600,
    };
    vi.spyOn(clientModule.apiClient, 'getFetchTcgAccount').mockResolvedValue({
      ...savedAccount,
    });
    vi.spyOn(
      clientModule.apiClient,
      'updateFetchTcgCredentials',
    ).mockResolvedValue(replacement);
    renderSettingsPage();
    await screen.findByDisplayValue(savedAccount.seller_username);
    await user.type(tokenInput(), 'replacement-token');
    await user.click(screen.getByRole('button', { name: 'Save token' }));

    await waitFor(() =>
      expect(
        (screen.getByLabelText('FetchTCG seller username') as HTMLInputElement)
          .value,
      ).toBe(replacement.seller_username),
    );
    expect(
      screen.getByText(
        `Last updated ${new Date(replacement.credentials_updated_at * 1000).toLocaleString()}`,
      ),
    ).toBeDefined();
    expect(clientModule.apiClient.updateSettings).not.toHaveBeenCalled();
  });

  it('keeps token entry available when the account read fails', async () => {
    vi.spyOn(clientModule.apiClient, 'getFetchTcgAccount').mockRejectedValue(
      new Error('FetchTCG unavailable'),
    );

    renderSettingsPage();

    const connection = screen.getByRole('region', {
      name: 'FetchTCG connection',
    });
    expect(
      await within(connection).findByText('FetchTCG unavailable'),
    ).toBeDefined();
    expect(
      within(connection).getByPlaceholderText('Enter refresh token'),
    ).toBeDefined();
    expect(await screen.findByPlaceholderText('No cutoff set')).toBeDefined();
  });

  it('keeps the account view available when the settings read fails', async () => {
    vi.spyOn(clientModule.apiClient, 'getSettings').mockRejectedValue(
      new Error('Settings unavailable'),
    );
    vi.spyOn(clientModule.apiClient, 'getFetchTcgAccount').mockResolvedValue({
      ...savedAccount,
    });

    renderSettingsPage();

    expect(await screen.findByText('Settings unavailable')).toBeDefined();
    expect(
      (
        (await screen.findByLabelText(
          'FetchTCG seller username',
        )) as HTMLInputElement
      ).value,
    ).toBe(savedAccount.seller_username);
  });

  it('keeps token submission pending until the account read completes', async () => {
    let resolveAccount!: (account: null) => void;
    vi.spyOn(clientModule.apiClient, 'getFetchTcgAccount').mockImplementation(
      () => new Promise<null>((resolve) => (resolveAccount = resolve)),
    );
    const user = userEvent.setup();
    renderSettingsPage();
    await screen.findByPlaceholderText('Enter refresh token');
    await user.type(tokenInput(), 'candidate-token');
    expect(screen.getByRole('button', { name: 'Save token' })).toHaveProperty(
      'disabled',
      true,
    );
    expect(
      clientModule.apiClient.updateFetchTcgCredentials,
    ).not.toHaveBeenCalled();

    resolveAccount(null);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Save token' })).toHaveProperty(
        'disabled',
        false,
      ),
    );
  });

  it('disables blank credential submission', async () => {
    renderSettingsPage();
    await screen.findByPlaceholderText('Enter refresh token');

    expect(screen.getByRole('button', { name: 'Save token' })).toHaveProperty(
      'disabled',
      true,
    );
    expect(
      clientModule.apiClient.updateFetchTcgCredentials,
    ).not.toHaveBeenCalled();
  });

  it('keeps the credential save pending until the request completes', async () => {
    let resolveSave!: (account: typeof savedAccount) => void;
    vi.spyOn(
      clientModule.apiClient,
      'updateFetchTcgCredentials',
    ).mockImplementation(
      () => new Promise((resolve) => (resolveSave = resolve)),
    );
    const user = userEvent.setup();
    renderSettingsPage();
    await screen.findByPlaceholderText('Enter refresh token');
    await user.type(tokenInput(), 'candidate-token');
    await user.click(screen.getByRole('button', { name: 'Save token' }));

    expect(screen.getByRole('button', { name: 'Save token' })).toHaveProperty(
      'disabled',
      true,
    );
    expect(tokenInput().value).toBe('candidate-token');
    resolveSave({ ...savedAccount });
    await waitFor(() => expect(tokenInput().value).toBe(''));
  });

  it('saves only the order tracking cutoff through settings', async () => {
    const user = userEvent.setup();
    renderSettingsPage();

    const cutoff = (await screen.findByLabelText(
      'Track orders after',
    )) as HTMLInputElement;
    await user.clear(cutoff);
    await user.type(cutoff, 'September 1, 2026');
    await user.click(screen.getByRole('button', { name: 'Save date' }));

    expect(clientModule.apiClient.updateSettings).toHaveBeenCalledWith({
      track_orders_after: Math.floor(new Date(2026, 8, 1).getTime() / 1000),
    });
    expect(
      clientModule.apiClient.updateFetchTcgCredentials,
    ).not.toHaveBeenCalled();
  });

  it('shows an existing order tracking cutoff without prompting another save', async () => {
    vi.spyOn(clientModule.apiClient, 'getSettings').mockResolvedValue({
      ...defaultSettings,
      track_orders_after: Math.floor(new Date(2026, 8, 1).getTime() / 1000),
    });

    renderSettingsPage();

    expect(
      ((await screen.findByLabelText('Track orders after')) as HTMLInputElement)
        .value,
    ).toBe('September 1, 2026');
    expect(screen.getByRole('button', { name: 'Save date' })).toHaveProperty(
      'disabled',
      true,
    );
  });
});
