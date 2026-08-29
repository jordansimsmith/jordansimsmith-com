import { getSession } from '../auth/session';
import type {
  ApiClient,
  Condition,
  ConfirmImportResponse,
  FindImportsParams,
  FindImportsResponse,
  FindOrdersResponse,
  FindSkusParams,
  FindSkusResponse,
  ImportDetail,
  ImportRow,
  ImportSummary,
  OrderDetail,
  PublishResponse,
  ReportResponse,
  SettingsResponse,
  SkuDetail,
  UpdateSettingsRequest,
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

    async updateSettings(
      update: UpdateSettingsRequest,
    ): Promise<SettingsResponse> {
      const response = await authenticatedFetch('/settings', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(update),
      });
      return response.json();
    },

    async createImport(filename: string, csv: string): Promise<ImportSummary> {
      const query = new URLSearchParams({ filename });
      const response = await authenticatedFetch(`/imports?${query}`, {
        method: 'POST',
        headers: { 'Content-Type': 'text/csv' },
        body: csv,
      });
      return response.json();
    },

    async findImports(
      params?: FindImportsParams,
    ): Promise<FindImportsResponse> {
      const query = new URLSearchParams();
      if (params?.continuation) {
        query.set('continuation', params.continuation);
      }
      const queryString = query.toString();
      const response = await authenticatedFetch(
        queryString ? `/imports?${queryString}` : '/imports',
      );
      return response.json();
    },

    async getImport(importId: string): Promise<ImportDetail> {
      const response = await authenticatedFetch(
        `/imports/${encodeURIComponent(importId)}`,
      );
      return response.json();
    },

    async updateImportRow(
      importId: string,
      position: number,
      condition: Condition,
    ): Promise<ImportRow> {
      const response = await authenticatedFetch(
        `/imports/${encodeURIComponent(importId)}/rows/${position}`,
        {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ condition }),
        },
      );
      return response.json();
    },

    async deleteImportRow(importId: string, position: number): Promise<void> {
      await authenticatedFetch(
        `/imports/${encodeURIComponent(importId)}/rows/${position}`,
        { method: 'DELETE' },
      );
    },

    async addRowPhoto(
      importId: string,
      position: number,
      jpeg: Blob,
    ): Promise<void> {
      await authenticatedFetch(
        `/imports/${encodeURIComponent(importId)}/rows/${position}/photos`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'image/jpeg' },
          body: jpeg,
        },
      );
    },

    async deleteRowPhoto(
      importId: string,
      position: number,
      photoId: string,
    ): Promise<void> {
      await authenticatedFetch(
        `/imports/${encodeURIComponent(importId)}/rows/${position}/photos/${encodeURIComponent(photoId)}`,
        { method: 'DELETE' },
      );
    },

    async deleteImport(importId: string): Promise<void> {
      await authenticatedFetch(`/imports/${encodeURIComponent(importId)}`, {
        method: 'DELETE',
      });
    },

    async confirmImport(importId: string): Promise<ConfirmImportResponse> {
      const response = await authenticatedFetch(
        `/imports/${encodeURIComponent(importId)}/confirm`,
        { method: 'POST' },
      );
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
    ): Promise<void> {
      const query = reason ? `?${new URLSearchParams({ reason })}` : '';
      await authenticatedFetch(
        `/skus/${encodeURIComponent(skuId)}/units/${sequenceNumber}${query}`,
        { method: 'DELETE' },
      );
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

    async findOrders(): Promise<FindOrdersResponse> {
      const response = await authenticatedFetch('/orders');
      return response.json();
    },

    async getOrder(orderId: string): Promise<OrderDetail> {
      const response = await authenticatedFetch(
        `/orders/${encodeURIComponent(orderId)}`,
      );
      return response.json();
    },

    async confirmOrder(orderId: string): Promise<OrderDetail> {
      const response = await authenticatedFetch(
        `/orders/${encodeURIComponent(orderId)}/confirm`,
        { method: 'POST' },
      );
      return response.json();
    },

    async createPublish(): Promise<void> {
      await authenticatedFetch('/publish', { method: 'POST' });
    },

    async getPublish(): Promise<PublishResponse> {
      const response = await authenticatedFetch('/publish');
      return response.json();
    },

    async getReport(): Promise<ReportResponse> {
      const response = await authenticatedFetch('/reports');
      return response.json();
    },

    async createReport(): Promise<void> {
      await authenticatedFetch('/reports', { method: 'POST' });
    },
  };
}
