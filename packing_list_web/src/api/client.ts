import { createFakeClient } from './fake-client';
import { createHttpClient } from './http-client';

export interface TemplateItem {
  name: string;
  category: string;
  quantity: number;
  tags: string[];
}

export interface TemplatesResponse {
  base_template: {
    base_template_id: string;
    name: string;
    items: TemplateItem[];
  };
  variations: Array<{
    variation_id: string;
    name: string;
    items: TemplateItem[];
  }>;
}

export interface ApiClient {
  getTemplates(): Promise<TemplatesResponse>;
}

export const apiClient: ApiClient = import.meta.env.PROD
  ? createHttpClient()
  : createFakeClient();
