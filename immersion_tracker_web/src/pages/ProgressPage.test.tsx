import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { Notifications, notifications } from '@mantine/notifications';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ProgressPage } from './ProgressPage';
import * as clientModule from '../api/client';
import * as sessionModule from '../auth/session';

function renderProgressPage() {
  return render(
    <MantineProvider>
      <Notifications />
      <MemoryRouter>
        <ProgressPage />
      </MemoryRouter>
    </MantineProvider>,
  );
}

const mockProgressResponse: clientModule.ProgressResponse = {
  total_hours_watched: 847,
  total_episodes_watched: 2534,
  total_movies_watched: 42,
  youtube_videos_watched: 156,
  spotify_episodes_watched: 89,
  episodes_watched_today: 3,
  movies_watched_today: 0,
  youtube_videos_watched_today: 2,
  spotify_episodes_watched_today: 1,
  days_since_first_episode: 730,
  weekly_trend_percentage: 12.5,
  daily_activity: [{ days_ago: 0, minutes_watched: 60 }],
  all_time_progress: [
    { label: 'Jan 2024', cumulative_hours: 100 },
    { label: 'Apr 2024', cumulative_hours: 400 },
    { label: 'Jul 2024', cumulative_hours: 847 },
  ],
  shows: [
    { name: 'Terrace House', episodes_watched: 312 },
    { name: 'Midnight Diner', episodes_watched: 156 },
    { name: 'Aggretsuko', episodes_watched: 98 },
    { name: 'Shirokuma Cafe', episodes_watched: 87 },
    { name: 'Rilakkuma', episodes_watched: 65 },
    { name: 'Extra Show', episodes_watched: 48 },
  ],
  youtube_channels: [
    { channel_name: 'Japanese Ammo', videos_watched: 45 },
    { channel_name: 'Nihongo no Mori', videos_watched: 38 },
  ],
  spotify_shows: [{ show_name: 'Real Japanese Podcast', episodes_watched: 32 }],
  movies: [{ name: 'Spirited Away' }, { name: 'Your Name' }],
};

describe('ProgressPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    notifications.clean();
    vi.spyOn(sessionModule, 'getSession').mockReturnValue({
      username: 'testuser',
      token: 'dGVzdHVzZXI6dGVzdHBhc3M=',
    });
  });

  afterEach(() => {
    cleanup();
    notifications.clean();
  });

  it('shows loading skeleton initially', () => {
    vi.spyOn(clientModule.apiClient, 'getProgress').mockImplementation(
      () => new Promise(() => {}),
    );

    renderProgressPage();

    expect(
      document.querySelectorAll('.mantine-Skeleton-root').length,
    ).toBeGreaterThan(0);
  });

  it('renders progress summary with total hours', async () => {
    vi.spyOn(clientModule.apiClient, 'getProgress').mockResolvedValue(
      mockProgressResponse,
    );

    renderProgressPage();

    await waitFor(() => {
      expect(screen.getByText('847')).toBeDefined();
    });
    expect(screen.getByText('total hours watched')).toBeDefined();
  });

  it('renders all content type sections', async () => {
    vi.spyOn(clientModule.apiClient, 'getProgress').mockResolvedValue(
      mockProgressResponse,
    );

    renderProgressPage();

    await waitFor(() => {
      expect(screen.getByText('Series')).toBeDefined();
    });
    expect(screen.getByText('Movies')).toBeDefined();
    expect(screen.getByText('YouTube')).toBeDefined();
    expect(screen.getByText('Spotify')).toBeDefined();
  });

  it('renders top tiles in series section', async () => {
    vi.spyOn(clientModule.apiClient, 'getProgress').mockResolvedValue(
      mockProgressResponse,
    );

    renderProgressPage();

    await waitFor(() => {
      expect(screen.getByText('Terrace House')).toBeDefined();
    });
    expect(screen.getByText('Midnight Diner')).toBeDefined();
    expect(screen.getByText('Aggretsuko')).toBeDefined();
  });

  it('shows See all button when more than 5 tiles exist', async () => {
    vi.spyOn(clientModule.apiClient, 'getProgress').mockResolvedValue(
      mockProgressResponse,
    );

    renderProgressPage();

    await waitFor(() => {
      expect(screen.getByText('Terrace House')).toBeDefined();
    });

    const seeAllButtons = screen.getAllByRole('button', { name: /see all/i });
    expect(seeAllButtons.length).toBeGreaterThan(0);
  });

  it('opens modal when See all is clicked', async () => {
    vi.spyOn(clientModule.apiClient, 'getProgress').mockResolvedValue(
      mockProgressResponse,
    );

    const user = userEvent.setup();
    renderProgressPage();

    await waitFor(() => {
      expect(screen.getByText('Terrace House')).toBeDefined();
    });

    const seeAllButtons = screen.getAllByRole('button', { name: /see all/i });
    await user.click(seeAllButtons[0]);

    await waitFor(() => {
      expect(screen.getByText('Extra Show')).toBeDefined();
    });
  });

  it('shows error alert when fetch fails', async () => {
    vi.spyOn(clientModule.apiClient, 'getProgress').mockRejectedValue(
      new Error('Network error'),
    );

    renderProgressPage();

    await waitFor(() => {
      const alerts = screen.getAllByRole('alert');
      const alertComponent = alerts.find((el) =>
        el.classList.contains('mantine-Alert-root'),
      );
      expect(alertComponent).toBeDefined();
      expect(alertComponent?.textContent).toContain('Network error');
    });
  });
});
