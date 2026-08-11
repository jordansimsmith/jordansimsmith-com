import type { ApiClient, SettingsResponse } from './client';

export function createFakeClient(): ApiClient {
  return {
    async getSettings(): Promise<SettingsResponse> {
      return { credential_set: false, updated_at: null };
    },
  };
}
