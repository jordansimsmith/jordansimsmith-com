import { describe, it, expect } from 'vitest';
import { formatSetNumber } from './card-label';

describe('formatSetNumber', () => {
  it('combines an uppercase set code with the collector number', () => {
    expect(formatSetNumber('bbd', '195')).toBe('BBD#195');
  });
});
