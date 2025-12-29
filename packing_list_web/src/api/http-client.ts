import { getSession } from '../auth/session';
import type { ApiClient, TemplatesResponse } from './client';

const BASE_URL =
  import.meta.env.VITE_API_BASE_URL ||
  'https://api.packing-list.jordansimsmith.com';

export function createHttpClient(): ApiClient {
  return {
    async getTemplates(): Promise<TemplatesResponse> {
      const session = getSession();
      if (!session) {
        throw new Error('Not authenticated');
      }

      const url = new URL(`${BASE_URL}/templates`);
      url.searchParams.set('user', session.username);

      const response = await fetch(url.toString(), {
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
