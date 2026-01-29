import { createFakeClient } from './fake-client';
import { createHttpClient } from './http-client';

export interface CumulativeProgress {
  label: string;
  cumulative_hours: number;
}

export interface DailyActivity {
  days_ago: number;
  minutes_watched: number;
}

export interface Show {
  show_id?: string;
  name: string | null;
  artwork_url?: string | null;
  episodes_watched: number;
}

export interface YoutubeChannel {
  channel_id?: string;
  channel_name: string | null;
  artwork_url?: string | null;
  videos_watched: number;
}

export interface SpotifyShow {
  show_id?: string;
  show_name: string | null;
  artwork_url?: string | null;
  episodes_watched: number;
}

export interface Movie {
  movie_id?: string;
  name: string | null;
  artwork_url?: string | null;
}

export interface ProgressResponse {
  total_hours_watched: number;
  total_episodes_watched: number;
  total_movies_watched: number;
  youtube_videos_watched: number;
  spotify_episodes_watched: number;
  episodes_watched_today: number;
  movies_watched_today: number;
  youtube_videos_watched_today: number;
  spotify_episodes_watched_today: number;
  days_since_first_episode: number;
  weekly_trend_percentage: number | null;
  daily_activity: DailyActivity[];
  all_time_progress: CumulativeProgress[];
  shows: Show[];
  youtube_channels: YoutubeChannel[];
  spotify_shows: SpotifyShow[];
  movies: Movie[];
}

export interface ApiClient {
  getProgress(): Promise<ProgressResponse>;
}

function shouldUseHttpClient(): boolean {
  if (import.meta.env.PROD) {
    return true;
  }
  return import.meta.env.VITE_API_IMPL === 'http';
}

export const apiClient: ApiClient = shouldUseHttpClient()
  ? createHttpClient()
  : createFakeClient();
