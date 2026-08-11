import { getSession } from '../auth/session';
import type {
  ApiClient,
  Condition,
  FindSkusParams,
  FindSkusResponse,
  SettingsResponse,
  SkuDetail,
  UpdateUnitResponse,
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

    async getSku(skuId: string): Promise<SkuDetail> {
      const response = await authenticatedFetch(
        `/skus/${encodeURIComponent(skuId)}`,
      );
      return response.json();
    },

    async deleteUnit(
      skuId: string,
      sequenceNumber: number,
      reason?: string,
    ): Promise<SkuDetail> {
      const query = reason ? `?${new URLSearchParams({ reason })}` : '';
      const response = await authenticatedFetch(
        `/skus/${encodeURIComponent(skuId)}/units/${sequenceNumber}${query}`,
        { method: 'DELETE' },
      );
      return response.json();
    },

    async updateUnit(
      skuId: string,
      sequenceNumber: number,
      condition: Condition,
    ): Promise<UpdateUnitResponse> {
      const response = await authenticatedFetch(
        `/skus/${encodeURIComponent(skuId)}/units/${sequenceNumber}`,
        {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ condition }),
        },
      );
      return response.json();
    },
  };
}
