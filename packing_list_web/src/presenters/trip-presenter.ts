import type { Trip, TripItem, TripItemStatus, ApiClient } from '../api/client';
import { normalizedName } from '../domain/normalize';
import { formatDateForApi } from '../domain/dates';
import {
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

export interface EditTripValues {
  name: string;
  destination: string;
  departure_date: Date;
  return_date: Date;
}

interface TripPresenterDeps {
  apiClient: ApiClient;
}

export class TripPresenter {
  private apiClient: ApiClient;

  constructor(deps: TripPresenterDeps) {
    this.apiClient = deps.apiClient;
  }

  async loadTrip(tripId: string): Promise<Trip> {
    const response = await this.apiClient.getTrip(tripId);
    return response.trip;
  }

  async saveTrip(trip: Trip): Promise<void> {
    await this.apiClient.updateTrip({
      trip_id: trip.trip_id,
      name: trip.name,
      destination: trip.destination,
      departure_date: trip.departure_date,
      return_date: trip.return_date,
      items: trip.items,
    });
  }

  updateStatus(trip: Trip, itemName: string, newStatus: TripItemStatus): Trip {
    const updatedItems = trip.items.map((item) =>
      item.name === itemName ? { ...item, status: newStatus } : item,
    );
    return { ...trip, items: updatedItems };
  }

  addItem(trip: Trip, values: AddItemValues): Trip {
    const newItem: TripItem = {
      name: values.name.trim(),
      category: values.category?.trim() || 'misc/uncategorised',
      quantity: values.quantity,
      tags: values.tags,
      status: 'unpacked',
    };
    const updatedItems = upsertTripItem(trip.items, newItem);
    return { ...trip, items: updatedItems };
  }

  removeItem(trip: Trip, itemName: string): Trip {
    const updatedItems = removeTripItem(trip.items, normalizedName(itemName));
    return { ...trip, items: updatedItems };
  }

  editItem(trip: Trip, editingItemKey: string, values: EditItemValues): Trip {
    const updatedItems = updateTripItem(trip.items, editingItemKey, {
      category: values.category?.trim() || 'misc',
      quantity: values.quantity,
      tags: values.tags,
    });
    return { ...trip, items: updatedItems };
  }

  editTripDetails(trip: Trip, values: EditTripValues): Trip {
    return {
      ...trip,
      name: values.name.trim(),
      destination: values.destination.trim(),
      departure_date: formatDateForApi(values.departure_date),
      return_date: formatDateForApi(values.return_date),
    };
  }
}
