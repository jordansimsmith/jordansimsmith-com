import { getSession } from '../auth/session';
import type { ApiClient, ProgressResponse } from './client';

const fakeProgressData: ProgressResponse = {
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
  daily_activity: [
    { days_ago: 6, minutes_watched: 45 },
    { days_ago: 5, minutes_watched: 90 },
    { days_ago: 4, minutes_watched: 60 },
    { days_ago: 3, minutes_watched: 120 },
    { days_ago: 2, minutes_watched: 30 },
    { days_ago: 1, minutes_watched: 75 },
    { days_ago: 0, minutes_watched: 105 },
  ],
  all_time_progress: [
    { label: 'Jan 2024', cumulative_hours: 50 },
    { label: 'Apr 2024', cumulative_hours: 150 },
    { label: 'Jul 2024', cumulative_hours: 320 },
    { label: 'Oct 2024', cumulative_hours: 520 },
    { label: 'Jan 2025', cumulative_hours: 720 },
    { label: 'Apr 2025', cumulative_hours: 847 },
  ],
  shows: [
    { name: 'Terrace House', episodes_watched: 312 },
    { name: 'Midnight Diner', episodes_watched: 156 },
    { name: 'Aggretsuko', episodes_watched: 98 },
    { name: 'Shirokuma Cafe', episodes_watched: 87 },
    { name: 'Rilakkuma and Kaoru', episodes_watched: 65 },
    { name: 'Samurai Gourmet', episodes_watched: 48 },
    { name: 'Kantaro: The Sweet Tooth Salaryman', episodes_watched: 36 },
    { name: null, episodes_watched: 24 },
  ],
  youtube_channels: [
    { channel_name: 'Japanese Ammo with Misa', videos_watched: 45 },
    { channel_name: 'Nihongo no Mori', videos_watched: 38 },
    { channel_name: 'Abroad in Japan', videos_watched: 28 },
    { channel_name: 'Japanese Pod 101', videos_watched: 22 },
    { channel_name: 'Dogen', videos_watched: 15 },
    { channel_name: null, videos_watched: 8 },
  ],
  spotify_shows: [
    { show_name: 'The Miku Real Japanese Podcast', episodes_watched: 32 },
    { show_name: 'Nihongo Con Teppei', episodes_watched: 28 },
    { show_name: 'Learn Japanese Pod', episodes_watched: 18 },
    { show_name: 'JapanesePod101', episodes_watched: 11 },
  ],
  movies: [
    { name: 'A Silent Voice' },
    { name: 'Howls Moving Castle' },
    { name: "Kiki's Delivery Service" },
    { name: 'My Neighbor Totoro' },
    { name: 'Princess Mononoke' },
    { name: 'Spirited Away' },
    { name: 'The Garden of Words' },
    { name: 'The Wind Rises' },
    { name: 'Weathering with You' },
    { name: 'Your Name' },
  ],
};

export function createFakeClient(): ApiClient {
  return {
    async getProgress(): Promise<ProgressResponse> {
      const session = getSession();
      if (!session) {
        throw new Error('Not authenticated');
      }

      return fakeProgressData;
    },
  };
}
