import { useEffect, useRef } from 'react';
import { Table } from '@mantine/core';
import type { SkuSummary } from '../api/client';

interface SkuTableProps {
  skus: SkuSummary[];
  selectedIndex: number;
  onOpen: (sku: SkuSummary) => void;
}

export function SkuTable({ skus, selectedIndex, onOpen }: SkuTableProps) {
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
          <Table.Th>Name</Table.Th>
          <Table.Th>Set</Table.Th>
          <Table.Th>#</Table.Th>
          <Table.Th>Finish</Table.Th>
          <Table.Th>Condition</Table.Th>
          <Table.Th>Price</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {skus.map((sku, index) => {
          const selected = index === selectedIndex;
          return (
            <Table.Tr
              key={sku.sku_id}
              ref={selected ? selectedRowRef : undefined}
              data-selected={selected}
              bg={selected ? 'var(--mantine-color-blue-light)' : undefined}
              onClick={() => onOpen(sku)}
              style={{ cursor: 'pointer' }}
            >
              <Table.Td fw={500}>{sku.name}</Table.Td>
              <Table.Td title={sku.set_name}>
                {sku.set_code.toUpperCase()}
              </Table.Td>
              <Table.Td>{sku.collector_number}</Table.Td>
              <Table.Td>{sku.finish}</Table.Td>
              <Table.Td>{sku.condition}</Table.Td>
              <Table.Td>
                {sku.last_published_price != null
                  ? `$${sku.last_published_price}`
                  : ''}
              </Table.Td>
            </Table.Tr>
          );
        })}
      </Table.Tbody>
    </Table>
  );
}
