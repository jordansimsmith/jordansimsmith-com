import { Badge } from '@mantine/core';
import type { OrderState } from '../api/client';

const STATE_COLORS: Record<OrderState, string> = {
  awaiting_payment: 'yellow',
  to_pick: 'blue',
  fulfilled: 'green',
  voided: 'red',
};

interface OrderStateBadgeProps {
  state: OrderState;
}

export function OrderStateBadge({ state }: OrderStateBadgeProps) {
  return (
    <Badge
      variant="light"
      color={STATE_COLORS[state]}
      style={{ flexShrink: 0, minWidth: 'max-content' }}
    >
      {state.replace('_', ' ')}
    </Badge>
  );
}
