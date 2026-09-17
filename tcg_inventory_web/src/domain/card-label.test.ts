import { describe, it, expect } from 'vitest';
import { finishNameWeight, formatSetNumber } from './card-label';

describe('formatSetNumber', () => {
  it('combines an uppercase set code with the collector number', () => {
    expect(formatSetNumber('bbd', '195')).toBe('BBD#195');
  });
});

describe('finishNameWeight', () => {
  it('bolds foil and etched names', () => {
    expect(finishNameWeight('foil')).toBe(700);
    expect(finishNameWeight('etched')).toBe(700);
    expect(finishNameWeight('normal')).toBe(500);
  });
});
