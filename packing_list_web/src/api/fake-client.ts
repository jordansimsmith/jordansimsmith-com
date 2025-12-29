import { getSession } from '../auth/session';
import type { ApiClient, TemplatesResponse, TripsResponse } from './client';

const trips = [
  {
    trip_id: 'trip-001',
    name: 'Japan 2025',
    destination: 'Tokyo',
    departure_date: '2025-03-15',
    return_date: '2025-03-29',
    created_at: 1735000000,
    updated_at: 1735000000,
  },
  {
    trip_id: 'trip-002',
    name: 'Ski trip',
    destination: 'Queenstown',
    departure_date: '2025-07-10',
    return_date: '2025-07-17',
    created_at: 1735100000,
    updated_at: 1735100000,
  },
  {
    trip_id: 'trip-003',
    name: 'Christmas in Europe',
    destination: 'Berlin',
    departure_date: '2025-12-20',
    return_date: '2026-01-05',
    created_at: 1735200000,
    updated_at: 1735200000,
  },
];

export function createFakeClient(): ApiClient {
  return {
    async getTemplates(): Promise<TemplatesResponse> {
      const session = getSession();
      if (!session) {
        throw new Error('Not authenticated');
      }

      return {
        base_template: {
          base_template_id: 'generic',
          name: 'generic',
          items: [
            {
              name: 'passport',
              category: 'travel',
              quantity: 1,
              tags: ['hand luggage'],
            },
            {
              name: 'phone charger',
              category: 'electronics',
              quantity: 1,
              tags: [],
            },
            {
              name: 'toothbrush',
              category: 'toiletries',
              quantity: 1,
              tags: [],
            },
          ],
        },
        variations: [
          {
            variation_id: 'skiing',
            name: 'skiing',
            items: [
              {
                name: 'ski jacket',
                category: 'clothes',
                quantity: 1,
                tags: [],
              },
            ],
          },
        ],
      };
    },

    async getTrips(): Promise<TripsResponse> {
      const session = getSession();
      if (!session) {
        throw new Error('Not authenticated');
      }

      const sorted = [...trips].sort((a, b) =>
        b.departure_date.localeCompare(a.departure_date),
      );

      return { trips: sorted };
    },
  };
}
