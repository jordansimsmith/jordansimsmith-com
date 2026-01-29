import { getSession } from '../auth/session';
import type { ApiClient, ProgressResponse } from './client';

const BASE_URL =
  import.meta.env.VITE_API_BASE_URL ||
  'https://api.immersion-tracker.jordansimsmith.com';

export function createHttpClient(): ApiClient {
  return {
    async getProgress(): Promise<ProgressResponse> {
      const session = getSession();
      if (!session) {
        throw new Error('Not authenticated');
      }

      const response = await fetch(`${BASE_URL}/progress`, {
        headers: {
          Authorization: `Basic ${session.token}`,
        },
      });

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
