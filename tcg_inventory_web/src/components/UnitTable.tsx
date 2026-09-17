import { ActionIcon, Badge, Group, Image, Table, Text } from '@mantine/core';
import { IconPencil, IconTrash } from '@tabler/icons-react';
import type { SkuUnit, UnitStatus } from '../api/client';
import classes from './UnitTable.module.css';

const STATUS_COLORS: Record<UnitStatus, string> = {
  in_stock: 'teal',
  reserved: 'yellow',
  sold: 'gray',
  removed: 'red',
};

const THUMB_SIZE = 40;

interface UnitTableProps {
  units: SkuUnit[];
  onRemove: (unit: SkuUnit) => void;
  onEditCondition: (unit: SkuUnit) => void;
}

export function UnitTable({
  units,
  onRemove,
  onEditCondition,
}: UnitTableProps) {
  return (
    <Table
      highlightOnHover
      verticalSpacing={4}
      horizontalSpacing="sm"
      fz="sm"
      className={classes.table}
    >
      <Table.Thead>
        <Table.Tr>
          <Table.Th>Sequence</Table.Th>
          <Table.Th>Location</Table.Th>
          <Table.Th>Status</Table.Th>
          <Table.Th>Photos</Table.Th>
          <Table.Th data-field="actions">Actions</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {units.map((unit) => (
          <Table.Tr key={unit.sequence_number}>
            <Table.Td data-field="sequence" data-label="Sequence">
              {unit.sequence_number}
            </Table.Td>
            <Table.Td data-field="location" data-label="Location" fw={600}>
              {unit.location}
            </Table.Td>
            <Table.Td data-field="status" data-label="Status">
              <Badge
                size="sm"
                variant="light"
                color={STATUS_COLORS[unit.status]}
              >
                {unit.status.replace('_', ' ')}
              </Badge>
            </Table.Td>
            <Table.Td data-field="photos" data-label="Photos">
              {unit.photos.length > 0 ? (
                <Group gap={6} wrap="wrap">
                  {unit.photos.map((photo) => (
                    <Image
                      key={photo.photo_id}
                      src={photo.url}
                      alt={`Listing photo ${photo.photo_id}`}
                      w={THUMB_SIZE}
                      h={THUMB_SIZE}
                      radius="sm"
                      fit="cover"
                    />
                  ))}
                </Group>
              ) : (
                <Text size="xs" c="dimmed">
                  No photos
                </Text>
              )}
            </Table.Td>
            <Table.Td data-field="actions">
              {unit.status === 'in_stock' && (
                <Group
                  gap="xs"
                  justify="flex-end"
                  wrap="wrap"
                  className={classes.actions}
                >
                  <ActionIcon
                    size={40}
                    variant="subtle"
                    onClick={() => onEditCondition(unit)}
                    aria-label="Edit condition"
                    title="Edit condition"
                  >
                    <IconPencil size={18} />
                  </ActionIcon>
                  <ActionIcon
                    size={40}
                    variant="subtle"
                    color="red"
                    onClick={() => onRemove(unit)}
                    aria-label="Remove"
                    title="Remove"
                  >
                    <IconTrash size={18} />
                  </ActionIcon>
                </Group>
              )}
            </Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}
