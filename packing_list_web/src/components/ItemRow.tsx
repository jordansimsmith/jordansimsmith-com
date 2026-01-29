import {
  Text,
  Group,
  Badge,
  ActionIcon,
  CloseButton,
  SegmentedControl,
  Stack,
  Box,
} from '@mantine/core';
import { IconPencil } from '@tabler/icons-react';
import type { TripItem, TripItemStatus } from '../api/client';

function getStatusColor(status: TripItemStatus): string {
  switch (status) {
    case 'packed':
      return 'teal';
    case 'pack-just-in-time':
      return 'yellow';
    case 'unpacked':
    default:
      return 'gray';
  }
}

interface ItemRowProps {
  item: TripItem;
  onEdit: (item: TripItem) => void;
  onRemove: (itemKey: string) => void;
  showStatusControl?: boolean;
  onStatusChange?: (itemName: string, newStatus: TripItemStatus) => void;
}

export function ItemRow({
  item,
  onEdit,
  onRemove,
  showStatusControl = false,
  onStatusChange,
}: ItemRowProps) {
  if (showStatusControl) {
    return (
      <Box py="sm" px="xs">
        <Group justify="space-between" wrap="nowrap" align="flex-start">
          <Stack gap={4} style={{ flex: 1 }}>
            <Group gap="xs">
              <Text
                fw={500}
                td={item.status === 'packed' ? 'line-through' : undefined}
                c={item.status === 'packed' ? 'dimmed' : undefined}
              >
                {item.name}
              </Text>
              {item.quantity > 1 && (
                <Badge size="sm" variant="light">
                  ×{item.quantity}
                </Badge>
              )}
            </Group>
            {item.tags.length > 0 && (
              <Group gap={4}>
                {item.tags.map((tag) => (
                  <Badge key={tag} size="xs" variant="outline" color="gray">
                    {tag}
                  </Badge>
                ))}
              </Group>
            )}
          </Stack>
          <Group gap="xs" wrap="nowrap">
            <ActionIcon
              size="xs"
              variant="subtle"
              aria-label={`Edit ${item.name}`}
              onClick={() => onEdit(item)}
            >
              <IconPencil size={14} />
            </ActionIcon>
            <CloseButton
              size="xs"
              aria-label={`Remove ${item.name}`}
              onClick={() => onRemove(item.name)}
            />
            <SegmentedControl
              size="xs"
              value={item.status}
              onChange={(value) =>
                onStatusChange?.(item.name, value as TripItemStatus)
              }
              data={[
                { label: 'Unpacked', value: 'unpacked' },
                { label: 'Pack later', value: 'pack-just-in-time' },
                { label: 'Packed', value: 'packed' },
              ]}
              color={getStatusColor(item.status)}
            />
          </Group>
        </Group>
      </Box>
    );
  }

  return (
    <Group gap="xs" pl="md" justify="space-between" wrap="nowrap">
      <Text size="sm" style={{ flexShrink: 1 }}>
        {item.name}
      </Text>
      <Group gap="xs" wrap="nowrap">
        {item.quantity > 1 && (
          <Badge size="xs" variant="light">
            ×{item.quantity}
          </Badge>
        )}
        {item.tags.map((tag) => (
          <Badge key={tag} size="xs" variant="outline" color="gray">
            {tag}
          </Badge>
        ))}
        <ActionIcon
          size="xs"
          variant="subtle"
          aria-label={`Edit ${item.name}`}
          onClick={() => onEdit(item)}
        >
          <IconPencil size={14} />
        </ActionIcon>
        <CloseButton
          size="xs"
          aria-label={`Remove ${item.name}`}
          onClick={() => onRemove(item.name)}
        />
      </Group>
    </Group>
  );
}
