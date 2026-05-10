import { getSession } from '../auth/session';
import type { ApiClient, SearchResponse } from './client';

const BASE_URL =
  import.meta.env.VITE_API_BASE_URL ||
  'https://api.japanese-dictionary.jordansimsmith.com';

export function createHttpClient(): ApiClient {
  return {
    async search(q: string): Promise<SearchResponse> {
      const session = getSession();
      if (!session) {
        throw new Error('Not authenticated');
      }

      const response = await fetch(
        `${BASE_URL}/search?q=${encodeURIComponent(q)}`,
        {
          headers: {
            Authorization: `Basic ${session.token}`,
          },
        },
      );

      if (!response.ok) {
        let message = `Request failed: ${response.statusText}`;
        try {
          const error = await response.json();
          message = error.message || message;
        } catch {
          // use default message
        }
        throw new Error(message);
      }

      return response.json();
    },
  };
}
