import { Badge } from '@mantine/core';
import type { OrderState } from '../api/client';

const STATE_COLORS: Record<OrderState, string> = {
  awaiting_payment: 'yellow',
  reserving: 'blue',
  voiding: 'blue',
  to_pick: 'blue',
  fulfilling: 'blue',
  fulfilled: 'green',
  voided: 'red',
};

interface OrderStateBadgeProps {
  state: OrderState;
}

export function OrderStateBadge({ state }: OrderStateBadgeProps) {
  return (
    <Badge variant="light" color={STATE_COLORS[state]}>
      {state.replace('_', ' ')}
    </Badge>
  );
}
