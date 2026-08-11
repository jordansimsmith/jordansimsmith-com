import { createFakeClient } from './fake-client';
import { createHttpClient } from './http-client';

export interface SettingsResponse {
  credential_set: boolean;
  updated_at: number | null;
}

export interface ApiClient {
  getSettings(): Promise<SettingsResponse>;
}

export const apiClient: ApiClient = import.meta.env.PROD
  ? createHttpClient()
  : createFakeClient();
