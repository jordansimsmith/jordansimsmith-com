import { getSession } from '../auth/session';
import type { ApiClient, TemplatesResponse } from './client';

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
  };
}
