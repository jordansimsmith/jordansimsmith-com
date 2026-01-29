import { render, screen, cleanup } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { Notifications, notifications } from '@mantine/notifications';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { App } from './App';
import * as sessionModule from './auth/session';
import * as clientModule from './api/client';

function renderApp() {
  return render(
    <MantineProvider>
      <Notifications />
      <App />
    </MantineProvider>,
  );
}

describe('App routing', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    notifications.clean();
  });

  afterEach(() => {
    cleanup();
    notifications.clean();
  });

  it('shows login page when not authenticated', () => {
    vi.spyOn(sessionModule, 'getSession').mockReturnValue(null);

    renderApp();

    expect(
      screen.getByRole('heading', { name: /immersion tracker/i }),
    ).toBeDefined();
    expect(screen.getByLabelText(/username/i)).toBeDefined();
  });

  it('redirects to progress page when authenticated', async () => {
    vi.spyOn(sessionModule, 'getSession').mockReturnValue({
      username: 'testuser',
      token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
    });
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

    renderApp();

    expect(screen.queryByLabelText(/username/i)).toBeNull();
  });
});
