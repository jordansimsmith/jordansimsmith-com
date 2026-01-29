import type { TripItem, TemplateItem } from '../api/client';
import { normalizedName } from './normalize';

export function mergeTemplateItemsIntoTripItems(
  existingItems: TripItem[],
  newItems: TemplateItem[],
): TripItem[] {
  const itemMap = new Map<string, TripItem>();

  for (const item of existingItems) {
    itemMap.set(normalizedName(item.name), { ...item });
  }

  for (const item of newItems) {
    const key = normalizedName(item.name);
    const existing = itemMap.get(key);
    if (existing) {
      existing.quantity += item.quantity;
      existing.tags = [...new Set([...existing.tags, ...item.tags])];
    } else {
      itemMap.set(key, {
        name: item.name,
        category: item.category,
        quantity: item.quantity,
        tags: [...item.tags],
        status: 'unpacked',
      });
    }
  }

  return Array.from(itemMap.values());
}

export function upsertTripItem(
  items: TripItem[],
  newItem: TripItem,
): TripItem[] {
  const key = normalizedName(newItem.name);
  const existingItem = items.find((item) => normalizedName(item.name) === key);

  if (existingItem) {
    return items.map((item) =>
      normalizedName(item.name) === key
        ? {
            ...item,
            quantity: item.quantity + newItem.quantity,
            tags: [...new Set([...item.tags, ...newItem.tags])],
          }
        : item,
    );
  }

  return [...items, newItem];
}

export function removeTripItem(items: TripItem[], itemKey: string): TripItem[] {
  return items.filter((item) => normalizedName(item.name) !== itemKey);
}

export function updateTripItem(
  items: TripItem[],
  itemKey: string,
  patch: Partial<TripItem>,
): TripItem[] {
  return items.map((item) =>
    normalizedName(item.name) === itemKey ? { ...item, ...patch } : item,
  );
}

export interface GroupedItems {
  grouped: Map<string, TripItem[]>;
  sortedCategories: string[];
}

export function groupAndSortItemsByCategory(items: TripItem[]): GroupedItems {
  const grouped = new Map<string, TripItem[]>();

  for (const item of items) {
    const category = item.category || 'misc';
    const existing = grouped.get(category) || [];
    existing.push(item);
    grouped.set(category, existing);
  }

  for (const [category, categoryItems] of grouped) {
    grouped.set(
      category,
      categoryItems.sort((a, b) => a.name.localeCompare(b.name)),
    );
  }

  const sortedCategories = Array.from(grouped.keys()).sort((a, b) => {
    if (a === 'misc') return 1;
    if (b === 'misc') return -1;
    return a.localeCompare(b);
  });

  return { grouped, sortedCategories };
}

export function getExistingCategories(items: TripItem[]): string[] {
  return Array.from(
    new Set(items.map((item) => item.category).filter(Boolean)),
  ).sort();
}

export function getExistingTags(items: TripItem[]): string[] {
  return Array.from(new Set(items.flatMap((item) => item.tags))).sort();
}
