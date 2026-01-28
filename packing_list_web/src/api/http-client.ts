import { getSession } from '../auth/session';
import type {
  ApiClient,
  CreateTripRequest,
  CreateTripResponse,
  GetTripResponse,
  TemplatesResponse,
  TripsResponse,
  UpdateTripRequest,
  UpdateTripResponse,
} from './client';

const BASE_URL =
  import.meta.env.VITE_API_BASE_URL ||
  'https://api.packing-list.jordansimsmith.com';

export function createHttpClient(): ApiClient {
  return {
    async getTemplates(): Promise<TemplatesResponse> {
      const session = getSession();
      if (!session) {
        throw new Error('Not authenticated');
      }

      const response = await fetch(`${BASE_URL}/templates`, {
        headers: {
          Authorization: `Basic ${session.token}`,
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

      return response.json();
    },

    async getTrips(): Promise<TripsResponse> {
      const session = getSession();
      if (!session) {
        throw new Error('Not authenticated');
      }

      const response = await fetch(`${BASE_URL}/trips`, {
        headers: {
          Authorization: `Basic ${session.token}`,
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

      return response.json();
    },

    async createTrip(request: CreateTripRequest): Promise<CreateTripResponse> {
      const session = getSession();
      if (!session) {
        throw new Error('Not authenticated');
      }

      const response = await fetch(`${BASE_URL}/trips`, {
        method: 'POST',
        headers: {
          Authorization: `Basic ${session.token}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(request),
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

      return response.json();
    },

    async getTrip(tripId: string): Promise<GetTripResponse> {
      const session = getSession();
      if (!session) {
        throw new Error('Not authenticated');
      }

      const response = await fetch(`${BASE_URL}/trips/${tripId}`, {
        headers: {
          Authorization: `Basic ${session.token}`,
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

      return response.json();
    },

    async updateTrip(request: UpdateTripRequest): Promise<UpdateTripResponse> {
      const session = getSession();
      if (!session) {
        throw new Error('Not authenticated');
      }

      const response = await fetch(`${BASE_URL}/trips/${request.trip_id}`, {
        method: 'PUT',
        headers: {
          Authorization: `Basic ${session.token}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(request),
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

      return response.json();
    },
  };
}
