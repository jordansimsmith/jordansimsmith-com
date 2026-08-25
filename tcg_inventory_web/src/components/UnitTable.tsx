import { Badge, Button, Group, Image, Table } from '@mantine/core';
import type { SkuUnit, UnitStatus } from '../api/client';

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
      striped
      highlightOnHover
      verticalSpacing={4}
      horizontalSpacing="sm"
      fz="sm"
    >
      <Table.Thead>
        <Table.Tr>
          <Table.Th>Sequence number</Table.Th>
          <Table.Th>Location</Table.Th>
          <Table.Th>Status</Table.Th>
          <Table.Th>Photos</Table.Th>
          <Table.Th />
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {units.map((unit) => (
          <Table.Tr key={unit.sequence_number}>
            <Table.Td>{unit.sequence_number}</Table.Td>
            <Table.Td>{unit.location}</Table.Td>
            <Table.Td>
              <Badge
                size="sm"
                variant="light"
                color={STATUS_COLORS[unit.status]}
              >
                {unit.status.replace('_', ' ')}
              </Badge>
            </Table.Td>
            <Table.Td>
              <Group gap={6} wrap="nowrap">
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
            </Table.Td>
            <Table.Td>
              {unit.status === 'in_stock' && (
                <Group gap="xs" justify="flex-end" wrap="nowrap">
                  <Button
                    size="compact-xs"
                    variant="subtle"
                    onClick={() => onEditCondition(unit)}
                  >
                    Edit condition
                  </Button>
                  <Button
                    size="compact-xs"
                    variant="subtle"
                    color="red"
                    onClick={() => onRemove(unit)}
                  >
                    Remove
                  </Button>
                </Group>
              )}
            </Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}
