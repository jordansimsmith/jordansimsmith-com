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

export type TripItemStatus = 'unpacked' | 'packed' | 'pack-just-in-time';

export interface TripItem {
  name: string;
  category: string;
  quantity: number;
  tags: string[];
  status: TripItemStatus;
}

export interface Trip {
  trip_id: string;
  name: string;
  destination: string;
  departure_date: string;
  return_date: string;
  items: TripItem[];
  created_at: number;
  updated_at: number;
}

export interface CreateTripRequest {
  name: string;
  destination: string;
  departure_date: string;
  return_date: string;
  items: TripItem[];
}

export interface CreateTripResponse {
  trip: Trip;
}

export interface GetTripResponse {
  trip: Trip;
}

export interface ApiClient {
  getTemplates(): Promise<TemplatesResponse>;
  getTrips(): Promise<TripsResponse>;
  createTrip(request: CreateTripRequest): Promise<CreateTripResponse>;
  getTrip(tripId: string): Promise<GetTripResponse>;
}

export const apiClient: ApiClient = import.meta.env.PROD
  ? createHttpClient()
  : createFakeClient();
