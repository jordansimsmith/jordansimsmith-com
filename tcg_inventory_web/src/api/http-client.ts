import { getSession } from '../auth/session';
import type {
  ApiClient,
  FindSkusParams,
  FindSkusResponse,
  SettingsResponse,
} from './client';

const BASE_URL =
  import.meta.env.VITE_API_BASE_URL ||
  'https://api.tcg-inventory.jordansimsmith.com';

async function authenticatedFetch(path: string, init?: RequestInit) {
  const session = getSession();
  if (!session) {
    throw new Error('Not authenticated');
  }

  const response = await fetch(`${BASE_URL}${path}`, {
    ...init,
    headers: {
      Authorization: `Basic ${session.token}`,
      ...init?.headers,
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

  return response;
}

export function createHttpClient(): ApiClient {
  return {
    async getSettings(): Promise<SettingsResponse> {
      const response = await authenticatedFetch('/settings');
      return response.json();
    },

    async findSkus(params?: FindSkusParams): Promise<FindSkusResponse> {
      const query = new URLSearchParams();
      if (params?.search) {
        query.set('search', params.search);
      }
      if (params?.continuation) {
        query.set('continuation', params.continuation);
      }
      const queryString = query.toString();
      const response = await authenticatedFetch(
        queryString ? `/skus?${queryString}` : '/skus',
      );
      return response.json();
    },
  };
}
