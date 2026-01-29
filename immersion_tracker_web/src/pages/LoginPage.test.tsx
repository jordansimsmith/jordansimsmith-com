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
          <Route path="/progress" element={<div>Progress page</div>} />
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
      screen.getByRole('heading', { name: /immersion tracker/i }),
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

  it('navigates to progress page on successful login', async () => {
    vi.spyOn(clientModule.apiClient, 'getProgress').mockResolvedValue({
      total_hours_watched: 100,
      total_episodes_watched: 500,
      total_movies_watched: 10,
      youtube_videos_watched: 20,
      spotify_episodes_watched: 15,
      episodes_watched_today: 2,
      movies_watched_today: 0,
      youtube_videos_watched_today: 1,
      spotify_episodes_watched_today: 0,
      days_since_first_episode: 365,
      weekly_trend_percentage: null,
      daily_activity: [],
      all_time_progress: [],
      shows: [],
      youtube_channels: [],
      spotify_shows: [],
      movies: [],
    });

    const user = userEvent.setup();
    renderLoginPage();

    await user.type(screen.getByLabelText(/username/i), 'testuser');
    await user.type(screen.getByLabelText(/password/i), 'testpass');
    await user.click(screen.getByRole('button', { name: /log in/i }));

    await waitFor(() => {
      expect(screen.getByText('Progress page')).toBeDefined();
    });
    expect(clientModule.apiClient.getProgress).toHaveBeenCalled();
  });

  it('shows error notification and clears session on failed login', async () => {
    vi.spyOn(clientModule.apiClient, 'getProgress').mockRejectedValue(
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
