import { describe, it, expect } from 'vitest';
import {
  finishNameClass,
  finishNameWeight,
  formatSetNumber,
} from './card-label';

describe('formatSetNumber', () => {
  it('combines an uppercase set code with the collector number', () => {
    expect(formatSetNumber('bbd', '195')).toBe('BBD#195');
  });
});

describe('finishNameClass', () => {
  it('maps foil and etched to their rainbow classes', () => {
    expect(finishNameClass('foil')).toBe('foil-finish');
    expect(finishNameClass('etched')).toBe('etched-finish');
    expect(finishNameClass('normal')).toBeUndefined();
  });
});

describe('finishNameWeight', () => {
  it('bolds foil and etched names', () => {
    expect(finishNameWeight('foil')).toBe(700);
    expect(finishNameWeight('etched')).toBe(700);
    expect(finishNameWeight('normal')).toBe(500);
  });
});
