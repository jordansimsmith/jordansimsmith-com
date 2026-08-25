import { describe, expect, it } from 'vitest';
import { compareToList, formatVsList, listedLineTotal } from './listPrice';

describe('listPrice', () => {
  it('compares offered totals against listed totals', () => {
    expect(compareToList('3.33', '3.50')).toBe('below');
    expect(compareToList('8.50', '8.50')).toBe('at');
    expect(compareToList('1.00', '0.80')).toBe('above');
  });

  it('formats the compact vs-list label', () => {
    expect(formatVsList('3.33', '3.50')).toBe('−5% vs list');
    expect(formatVsList('10.90', '13.00')).toBe('−16% vs list');
    expect(formatVsList('1.00', '0.80')).toBe('+25% vs list');
  });

  it('multiplies per-unit listed price by quantity', () => {
    expect(listedLineTotal('2.00', 2)).toBe('4.00');
    expect(listedLineTotal('5.00', 1)).toBe('5.00');
  });
});
