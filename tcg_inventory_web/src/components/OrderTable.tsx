import { useEffect, useRef } from 'react';
import { Table } from '@mantine/core';
import type { OrderSummary } from '../api/client';
import { OrderStateBadge } from './OrderStateBadge';

interface OrderTableProps {
  orders: OrderSummary[];
  selectedIndex: number;
  onOpen: (order: OrderSummary) => void;
}

export function OrderTable({ orders, selectedIndex, onOpen }: OrderTableProps) {
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
          <Table.Th>Order</Table.Th>
          <Table.Th>State</Table.Th>
          <Table.Th ta="right">Cards</Table.Th>
          <Table.Th ta="right">Total</Table.Th>
          <Table.Th>Delivery</Table.Th>
          <Table.Th>Accepted</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {orders.map((order, index) => {
          const selected = index === selectedIndex;
          return (
            <Table.Tr
              key={order.order_id}
              ref={selected ? selectedRowRef : undefined}
              data-selected={selected}
              bg={selected ? 'var(--mantine-color-blue-light)' : undefined}
              onClick={() => onOpen(order)}
              style={{ cursor: 'pointer' }}
            >
              <Table.Td fw={500}>{order.order_id}</Table.Td>
              <Table.Td>
                <OrderStateBadge state={order.state} />
              </Table.Td>
              <Table.Td ta="right">{order.unit_count}</Table.Td>
              <Table.Td ta="right">${order.total_price}</Table.Td>
              <Table.Td>{order.delivery_mode}</Table.Td>
              <Table.Td>
                {new Date(order.accepted_at * 1000).toLocaleString()}
              </Table.Td>
            </Table.Tr>
          );
        })}
      </Table.Tbody>
    </Table>
  );
}
