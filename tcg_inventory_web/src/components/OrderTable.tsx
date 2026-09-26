import { Table } from '@mantine/core';
import type { OrderSummary } from '../api/client';
import { formatDeliveryMode } from '../domain/deliveryMode';
import { OrderStateBadge } from './OrderStateBadge';
import classes from './CollectionTable.module.css';

interface OrderTableProps {
  orders: OrderSummary[];
  onOpen: (order: OrderSummary) => void;
}

export function OrderTable({ orders, onOpen }: OrderTableProps) {
  return (
    <Table
      highlightOnHover
      verticalSpacing={4}
      horizontalSpacing="sm"
      fz="sm"
      className={`${classes.table} ${classes.orders}`}
    >
      <Table.Thead>
        <Table.Tr>
          <Table.Th>Order</Table.Th>
          <Table.Th>State</Table.Th>
          <Table.Th ta="right">Units</Table.Th>
          <Table.Th ta="right">Cards</Table.Th>
          <Table.Th>Delivery</Table.Th>
          <Table.Th>Accepted</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {orders.map((order) => (
          <Table.Tr
            key={order.order_id}
            onClick={() => onOpen(order)}
            style={{ cursor: 'pointer' }}
          >
            <Table.Td fw={500} data-field="order">
              {order.order_id}
            </Table.Td>
            <Table.Td data-field="state" data-label="State">
              <OrderStateBadge state={order.state} />
            </Table.Td>
            <Table.Td ta="right" data-field="units" data-label="Units">
              {order.unit_count}
            </Table.Td>
            <Table.Td ta="right" data-field="cards" data-label="Cards">
              {order.items_total_price ? `$${order.items_total_price}` : '—'}
            </Table.Td>
            <Table.Td data-field="delivery" data-label="Delivery">
              {formatDeliveryMode(order.delivery_mode)}
            </Table.Td>
            <Table.Td data-field="accepted" data-label="Accepted">
              {new Date(order.accepted_at * 1000).toLocaleString()}
            </Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}
