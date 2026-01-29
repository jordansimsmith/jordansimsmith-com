import type {
  TemplatesResponse,
  TripItem,
  CreateTripRequest,
  ApiClient,
} from '../api/client';
import { normalizedName } from '../domain/normalize';
import { formatDateForApi } from '../domain/dates';
import {
  mergeTemplateItemsIntoTripItems,
  upsertTripItem,
  removeTripItem,
  updateTripItem,
} from '../domain/items';

export interface AddItemValues {
  name: string;
  category: string;
  quantity: number;
  tags: string[];
}

export interface EditItemValues {
  category: string;
  quantity: number;
  tags: string[];
}

export interface CreateTripValues {
  name: string;
  destination: string;
  departure_date: Date;
  return_date: Date;
}

export interface ApplyVariationResult {
  items: TripItem[];
  addedVariations: Set<string>;
}

export interface ResetResult {
  items: TripItem[];
  addedVariations: Set<string>;
}

interface CreateTripPresenterDeps {
  apiClient: ApiClient;
}

export class CreateTripPresenter {
  private apiClient: ApiClient;

  constructor(deps: CreateTripPresenterDeps) {
    this.apiClient = deps.apiClient;
  }

  async loadTemplates(): Promise<TemplatesResponse> {
    return this.apiClient.getTemplates();
  }

  getBaseItems(templates: TemplatesResponse): TripItem[] {
    return templates.base_template.items.map((item) => ({
      ...item,
      status: 'unpacked' as const,
    }));
  }

  applyVariation(
    templates: TemplatesResponse,
    items: TripItem[],
    addedVariations: Set<string>,
    variationId: string,
  ): ApplyVariationResult | null {
    if (addedVariations.has(variationId)) return null;

    const variation = templates.variations.find(
      (v) => v.variation_id === variationId,
    );
    if (!variation) return null;

    return {
      items: mergeTemplateItemsIntoTripItems(items, variation.items),
      addedVariations: new Set([...addedVariations, variationId]),
    };
  }

  resetToBaseTemplate(templates: TemplatesResponse): ResetResult {
    return {
      items: this.getBaseItems(templates),
      addedVariations: new Set(),
    };
  }

  addItem(items: TripItem[], values: AddItemValues): TripItem[] {
    const newItem: TripItem = {
      name: values.name.trim(),
      category: values.category?.trim() || 'misc/uncategorised',
      quantity: values.quantity,
      tags: values.tags,
      status: 'unpacked',
    };
    return upsertTripItem(items, newItem);
  }

  editItem(
    items: TripItem[],
    editingItemKey: string,
    values: EditItemValues,
  ): TripItem[] {
    return updateTripItem(items, normalizedName(editingItemKey), {
      category: values.category?.trim() || 'misc',
      quantity: values.quantity,
      tags: values.tags,
    });
  }

  removeItem(items: TripItem[], itemName: string): TripItem[] {
    return removeTripItem(items, normalizedName(itemName));
  }

  async createTrip(
    values: CreateTripValues,
    items: TripItem[],
  ): Promise<string> {
    const request: CreateTripRequest = {
      name: values.name.trim(),
      destination: values.destination.trim(),
      departure_date: formatDateForApi(values.departure_date),
      return_date: formatDateForApi(values.return_date),
      items,
    };
    const response = await this.apiClient.createTrip(request);
    return response.trip.trip_id;
  }
}
