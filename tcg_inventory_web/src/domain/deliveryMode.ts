const DELIVERY_MODE_LABELS: Record<string, string> = {
  DELIVERY: 'Delivery',
  PICKUP: 'Pickup',
};

export function formatDeliveryMode(deliveryMode: string): string {
  const label = DELIVERY_MODE_LABELS[deliveryMode];
  if (label == null) {
    throw new Error(`unknown delivery mode: ${deliveryMode}`);
  }
  return label;
}
