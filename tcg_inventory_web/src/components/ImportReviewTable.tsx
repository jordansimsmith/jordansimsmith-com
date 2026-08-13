import { useEffect, useRef } from 'react';
import { Badge, NativeSelect, Table, Text } from '@mantine/core';
import type { Condition, ImportRow, RowDecision } from '../api/client';
import { CONDITIONS } from '../api/client';

const DECISION_COLORS: Record<RowDecision, string> = {
  keep: 'green',
  discard: 'gray',
  review: 'yellow',
};

interface ImportReviewTableProps {
  rows: ImportRow[];
  selectedIndex: number;
  onSelect: (index: number) => void;
  editable?: boolean;
  onConditionChange?: (position: number, condition: Condition) => void;
}

export function ImportReviewTable({
  rows,
  selectedIndex,
  onSelect,
  editable = false,
  onConditionChange,
}: ImportReviewTableProps) {
  const selectedRowRef = useRef<HTMLTableRowElement>(null);

  useEffect(() => {
    selectedRowRef.current?.scrollIntoView({ block: 'nearest' });
  }, [selectedIndex]);

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
          <Table.Th ta="right">Position</Table.Th>
          <Table.Th>Name</Table.Th>
          <Table.Th>Set</Table.Th>
          <Table.Th>#</Table.Th>
          <Table.Th>Finish</Table.Th>
          <Table.Th>Condition</Table.Th>
          <Table.Th ta="right">Market</Table.Th>
          <Table.Th ta="right">Suggested</Table.Th>
          <Table.Th>Decision</Table.Th>
          <Table.Th>Reason</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {rows.map((row, index) => {
          const selected = index === selectedIndex;
          return (
            <Table.Tr
              key={row.position}
              ref={selected ? selectedRowRef : undefined}
              data-selected={selected}
              bg={selected ? 'var(--mantine-color-blue-light)' : undefined}
              onClick={() => onSelect(index)}
            >
              <Table.Td ta="right" c="dimmed">
                {row.position}
              </Table.Td>
              <Table.Td fw={500}>{row.name}</Table.Td>
              <Table.Td title={row.set_name}>
                {row.set_code.toUpperCase()}
              </Table.Td>
              <Table.Td>{row.collector_number}</Table.Td>
              <Table.Td>{row.finish}</Table.Td>
              <Table.Td>
                {editable && onConditionChange ? (
                  <NativeSelect
                    size="xs"
                    value={row.condition}
                    data={CONDITIONS}
                    onChange={(event) =>
                      onConditionChange(
                        row.position,
                        event.currentTarget.value as Condition,
                      )
                    }
                    onClick={(event) => event.stopPropagation()}
                    aria-label={`Condition for row ${row.position}`}
                  />
                ) : (
                  row.condition
                )}
              </Table.Td>
              <Table.Td ta="right">
                {row.market_price !== null && `$${row.market_price}`}
              </Table.Td>
              <Table.Td ta="right">
                {row.suggested_price !== null && `$${row.suggested_price}`}
              </Table.Td>
              <Table.Td>
                {row.decision ? (
                  <Badge variant="light" color={DECISION_COLORS[row.decision]}>
                    {row.decision}
                  </Badge>
                ) : (
                  <Text size="xs" c="dimmed">
                    pending
                  </Text>
                )}
              </Table.Td>
              <Table.Td c="dimmed">{row.decision_reason}</Table.Td>
            </Table.Tr>
          );
        })}
      </Table.Tbody>
    </Table>
  );
}
