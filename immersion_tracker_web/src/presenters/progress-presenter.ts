import type {
  ProgressResponse,
  Show,
  YoutubeChannel,
  SpotifyShow,
  Movie,
  CumulativeProgress,
  ApiClient,
} from '../api/client';

export interface SummaryViewModel {
  totalHoursWatched: number;
}

export interface ChartPoint {
  label: string;
  cumulativeHours: number;
}

export interface TileViewModel {
  id: string;
  name: string;
  artworkUrl: string | null;
  count: number | null;
}

export interface ContentSectionViewModel {
  title: string;
  totalCount: number;
  topTiles: TileViewModel[];
  allTiles: TileViewModel[];
}

export interface ProgressViewModel {
  summary: SummaryViewModel;
  chartPoints: ChartPoint[];
  seriesSection: ContentSectionViewModel;
  moviesSection: ContentSectionViewModel;
  youtubeSection: ContentSectionViewModel;
  spotifySection: ContentSectionViewModel;
}

interface ProgressPresenterDeps {
  apiClient: ApiClient;
}

export class ProgressPresenter {
  private apiClient: ApiClient;

  constructor(deps: ProgressPresenterDeps) {
    this.apiClient = deps.apiClient;
  }

  async loadProgress(): Promise<ProgressViewModel> {
    const response = await this.apiClient.getProgress();
    return this.transformResponse(response);
  }

  private transformResponse(response: ProgressResponse): ProgressViewModel {
    const showTiles = response.shows.map((show, i) => this.showToTile(show, i));
    const youtubeTiles = response.youtube_channels.map((channel, i) =>
      this.youtubeChannelToTile(channel, i),
    );
    const spotifyTiles = response.spotify_shows.map((show, i) =>
      this.spotifyShowToTile(show, i),
    );
    const movieTiles = response.movies.map((movie, i) =>
      this.movieToTile(movie, i),
    );

    return {
      summary: {
        totalHoursWatched: response.total_hours_watched,
      },
      chartPoints: this.chartPointsFromProgress(response.all_time_progress),
      seriesSection: {
        title: 'Series',
        totalCount: response.total_episodes_watched,
        topTiles: showTiles.slice(0, 5),
        allTiles: showTiles,
      },
      moviesSection: {
        title: 'Movies',
        totalCount: response.total_movies_watched,
        topTiles: movieTiles.slice(0, 5),
        allTiles: movieTiles,
      },
      youtubeSection: {
        title: 'YouTube',
        totalCount: response.youtube_videos_watched,
        topTiles: youtubeTiles.slice(0, 5),
        allTiles: youtubeTiles,
      },
      spotifySection: {
        title: 'Spotify',
        totalCount: response.spotify_episodes_watched,
        topTiles: spotifyTiles.slice(0, 5),
        allTiles: spotifyTiles,
      },
    };
  }

  private showToTile(show: Show, index: number): TileViewModel {
    return {
      id: show.show_id ?? `show-${index}`,
      name: show.name ?? 'Unknown show',
      artworkUrl: show.artwork_url ?? null,
      count: show.episodes_watched,
    };
  }

  private youtubeChannelToTile(
    channel: YoutubeChannel,
    index: number,
  ): TileViewModel {
    return {
      id: channel.channel_id ?? `channel-${index}`,
      name: channel.channel_name ?? 'Unknown channel',
      artworkUrl: channel.artwork_url ?? null,
      count: channel.videos_watched,
    };
  }

  private spotifyShowToTile(show: SpotifyShow, index: number): TileViewModel {
    return {
      id: show.show_id ?? `spotify-${index}`,
      name: show.show_name ?? 'Unknown show',
      artworkUrl: show.artwork_url ?? null,
      count: show.episodes_watched,
    };
  }

  private movieToTile(movie: Movie, index: number): TileViewModel {
    return {
      id: movie.movie_id ?? `movie-${index}`,
      name: movie.name ?? 'Unknown movie',
      artworkUrl: movie.artwork_url ?? null,
      count: null,
    };
  }

  private chartPointsFromProgress(
    allTimeProgress: CumulativeProgress[],
  ): ChartPoint[] {
    return allTimeProgress.map((p) => ({
      label: p.label,
      cumulativeHours: p.cumulative_hours,
    }));
  }
}
