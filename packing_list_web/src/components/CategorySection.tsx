import { Text, Stack, Divider, Box } from '@mantine/core';
import type { TripItem, TripItemStatus } from '../api/client';
import { ItemRow } from './ItemRow';

interface CategorySectionProps {
  category: string;
  items: TripItem[];
  onEdit: (item: TripItem) => void;
  onRemove: (itemKey: string) => void;
  showStatusControl?: boolean;
  onStatusChange?: (itemName: string, newStatus: TripItemStatus) => void;
}

export function CategorySection({
  category,
  items,
  onEdit,
  onRemove,
  showStatusControl = false,
  onStatusChange,
}: CategorySectionProps) {
  if (showStatusControl) {
    return (
      <Box>
        <Text fw={600} size="sm" c="dimmed" tt="uppercase" mb="xs">
          {category}
        </Text>
        <Stack gap={0}>
          {items.map((item, index) => (
            <div key={item.name}>
              <ItemRow
                item={item}
                onEdit={onEdit}
                onRemove={onRemove}
                showStatusControl
                onStatusChange={onStatusChange}
              />
              {index < items.length - 1 && <Divider />}
            </div>
          ))}
        </Stack>
      </Box>
    );
  }

  return (
    <Stack gap="xs">
      <Text fw={500} tt="capitalize">
        {category}
      </Text>
      {items.map((item) => (
        <ItemRow
          key={item.name}
          item={item}
          onEdit={onEdit}
          onRemove={onRemove}
        />
      ))}
    </Stack>
  );
}
