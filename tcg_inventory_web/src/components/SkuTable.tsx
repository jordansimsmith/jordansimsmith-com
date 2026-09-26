import { Table } from '@mantine/core';
import type { SkuSummary } from '../api/client';
import classes from './CollectionTable.module.css';

interface SkuTableProps {
  skus: SkuSummary[];
  onOpen: (sku: SkuSummary) => void;
}

export function SkuTable({ skus, onOpen }: SkuTableProps) {
  return (
    <Table
      highlightOnHover
      verticalSpacing={4}
      horizontalSpacing="sm"
      fz="sm"
      className={`${classes.table} ${classes.skus}`}
    >
      <Table.Thead>
        <Table.Tr>
          <Table.Th>Name</Table.Th>
          <Table.Th>Set</Table.Th>
          <Table.Th>#</Table.Th>
          <Table.Th>Finish</Table.Th>
          <Table.Th>Condition</Table.Th>
          <Table.Th ta="right">Price</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {skus.map((sku) => (
          <Table.Tr
            key={sku.sku_id}
            onClick={() => onOpen(sku)}
            style={{ cursor: 'pointer' }}
          >
            <Table.Td fw={500} data-field="name">
              {sku.name}
            </Table.Td>
            <Table.Td title={sku.set_name} data-field="set" data-label="Set">
              {sku.set_code.toUpperCase()}
            </Table.Td>
            <Table.Td data-field="collector" data-label="Number">
              {sku.collector_number}
            </Table.Td>
            <Table.Td data-field="finish" data-label="Finish">
              {sku.finish}
            </Table.Td>
            <Table.Td data-field="condition" data-label="Condition">
              {sku.condition}
            </Table.Td>
            <Table.Td ta="right" data-field="price" data-label="Price">
              {sku.last_published_price != null
                ? `$${sku.last_published_price}`
                : ''}
            </Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}
