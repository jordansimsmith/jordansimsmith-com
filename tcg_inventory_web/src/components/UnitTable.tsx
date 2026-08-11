import { Badge, Button, Group, Table } from '@mantine/core';
import type { SkuUnit, UnitStatus } from '../api/client';

const STATUS_COLORS: Record<UnitStatus, string> = {
  in_stock: 'teal',
  reserved: 'yellow',
  sold: 'gray',
  removed: 'red',
};

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
