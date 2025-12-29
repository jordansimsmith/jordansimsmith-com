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

export interface TripSummary {
  trip_id: string;
  name: string;
  destination: string;
  departure_date: string;
  return_date: string;
  created_at: number;
  updated_at: number;
}

export interface TripsResponse {
  trips: TripSummary[];
}

export interface ApiClient {
  getTemplates(): Promise<TemplatesResponse>;
  getTrips(): Promise<TripsResponse>;
}

export const apiClient: ApiClient = import.meta.env.PROD
  ? createHttpClient()
  : createFakeClient();
