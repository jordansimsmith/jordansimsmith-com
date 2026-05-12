import { getSession } from '../auth/session';
import type { ApiClient, BookmarksResponse, SearchResponse } from './client';

const BASE_URL =
  import.meta.env.VITE_API_BASE_URL ||
  'https://api.japanese-dictionary.jordansimsmith.com';

async function request(
  path: string,
  init: RequestInit = {},
): Promise<Response> {
  const session = getSession();
  if (!session) {
    throw new Error('Not authenticated');
  }

  const headers: Record<string, string> = {
    ...((init.headers as Record<string, string> | undefined) ?? {}),
    Authorization: `Basic ${session.token}`,
  };

  const response = await fetch(`${BASE_URL}${path}`, { ...init, headers });

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

  return response;
}

export function createHttpClient(): ApiClient {
  return {
    async search(q: string): Promise<SearchResponse> {
      const response = await request(`/search?q=${encodeURIComponent(q)}`);
      return response.json();
    },

    async findBookmarks(): Promise<BookmarksResponse> {
      const response = await request('/bookmarks');
      return response.json();
    },

    async createBookmark(sequence: number): Promise<void> {
      await request(`/bookmarks/${encodeURIComponent(String(sequence))}`, {
        method: 'PUT',
      });
    },

    async deleteBookmark(sequence: number): Promise<void> {
      await request(`/bookmarks/${encodeURIComponent(String(sequence))}`, {
        method: 'DELETE',
      });
    },
  };
}
