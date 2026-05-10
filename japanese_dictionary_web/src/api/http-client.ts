import type { ApiClient } from './client';

export function createHttpClient(): ApiClient {
  return {
    async search(): Promise<never> {
      throw new Error('not implemented');
    },
  };
}
