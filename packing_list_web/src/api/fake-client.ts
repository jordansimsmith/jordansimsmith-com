import { getSession } from '../auth/session';
import type {
  ApiClient,
  CreateTripRequest,
  CreateTripResponse,
  GetTripResponse,
  TemplatesResponse,
  Trip,
  TripsResponse,
} from './client';

const trips: Trip[] = [
  {
    trip_id: 'trip-001',
    name: 'Japan 2025',
    destination: 'Tokyo',
    departure_date: '2025-03-15',
    return_date: '2025-03-29',
    items: [
      {
        name: 'Passport',
        category: 'travel',
        quantity: 1,
        tags: ['hand luggage', 'essential'],
        status: 'packed',
      },
      {
        name: 'Phone charger',
        category: 'electronics',
        quantity: 1,
        tags: ['hand luggage'],
        status: 'unpacked',
      },
      {
        name: 'Power adapter',
        category: 'electronics',
        quantity: 2,
        tags: [],
        status: 'unpacked',
      },
      {
        name: 'T-shirts',
        category: 'clothes',
        quantity: 5,
        tags: [],
        status: 'unpacked',
      },
      {
        name: 'Jeans',
        category: 'clothes',
        quantity: 2,
        tags: [],
        status: 'packed',
      },
      {
        name: 'Toothbrush',
        category: 'toiletries',
        quantity: 1,
        tags: [],
        status: 'unpacked',
      },
      {
        name: 'Toothpaste',
        category: 'toiletries',
        quantity: 1,
        tags: [],
        status: 'pack-just-in-time',
      },
      {
        name: 'Sunglasses',
        category: 'accessories',
        quantity: 1,
        tags: ['hand luggage'],
        status: 'unpacked',
      },
      {
        name: 'Wallet',
        category: 'travel',
        quantity: 1,
        tags: ['hand luggage', 'essential'],
        status: 'pack-just-in-time',
      },
      {
        name: 'Camera',
        category: 'electronics',
        quantity: 1,
        tags: ['hand luggage'],
        status: 'unpacked',
      },
    ],
    created_at: 1735000000,
    updated_at: 1735000000,
  },
  {
    trip_id: 'trip-002',
    name: 'Ski trip',
    destination: 'Queenstown',
    departure_date: '2025-07-10',
    return_date: '2025-07-17',
    items: [
      {
        name: 'Ski jacket',
        category: 'clothes',
        quantity: 1,
        tags: [],
        status: 'unpacked',
      },
      {
        name: 'Ski pants',
        category: 'clothes',
        quantity: 1,
        tags: [],
        status: 'unpacked',
      },
      {
        name: 'Thermal underwear',
        category: 'clothes',
        quantity: 3,
        tags: [],
        status: 'unpacked',
      },
      {
        name: 'Goggles',
        category: 'gear',
        quantity: 1,
        tags: [],
        status: 'packed',
      },
      {
        name: 'Gloves',
        category: 'gear',
        quantity: 1,
        tags: [],
        status: 'packed',
      },
      {
        name: 'Sunscreen',
        category: 'toiletries',
        quantity: 1,
        tags: [],
        status: 'unpacked',
      },
    ],
    created_at: 1735100000,
    updated_at: 1735100000,
  },
  {
    trip_id: 'trip-003',
    name: 'Christmas in Europe',
    destination: 'Berlin',
    departure_date: '2025-12-20',
    return_date: '2026-01-05',
    items: [
      {
        name: 'Passport',
        category: 'travel',
        quantity: 1,
        tags: ['hand luggage'],
        status: 'unpacked',
      },
      {
        name: 'Winter coat',
        category: 'clothes',
        quantity: 1,
        tags: [],
        status: 'unpacked',
      },
      {
        name: 'Scarf',
        category: 'clothes',
        quantity: 1,
        tags: [],
        status: 'unpacked',
      },
      {
        name: 'Beanie',
        category: 'clothes',
        quantity: 1,
        tags: [],
        status: 'unpacked',
      },
    ],
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

    async createTrip(request: CreateTripRequest): Promise<CreateTripResponse> {
      const session = getSession();
      if (!session) {
        throw new Error('Not authenticated');
      }

      const now = Math.floor(Date.now() / 1000);
      const trip: Trip = {
        trip_id: crypto.randomUUID(),
        name: request.name,
        destination: request.destination,
        departure_date: request.departure_date,
        return_date: request.return_date,
        items: request.items,
        created_at: now,
        updated_at: now,
      };

      trips.push(trip);

      return { trip };
    },

    async getTrip(tripId: string): Promise<GetTripResponse> {
      const session = getSession();
      if (!session) {
        throw new Error('Not authenticated');
      }

      const trip = trips.find((t) => t.trip_id === tripId);
      if (!trip) {
        throw new Error('Not Found');
      }

      return { trip };
    },
  };
}
