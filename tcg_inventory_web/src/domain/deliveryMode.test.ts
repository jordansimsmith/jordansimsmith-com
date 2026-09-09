import { describe, expect, it } from 'vitest';
import { formatDeliveryMode } from './deliveryMode';

describe('formatDeliveryMode', () => {
  it('maps known FetchTCG delivery modes', () => {
    expect(formatDeliveryMode('DELIVERY')).toBe('Delivery');
    expect(formatDeliveryMode('PICKUP')).toBe('Pickup');
  });

  it('throws when the delivery mode is unknown', () => {
    expect(() => formatDeliveryMode('SHIPPING')).toThrow(
      'unknown delivery mode: SHIPPING',
    );
  });
});
