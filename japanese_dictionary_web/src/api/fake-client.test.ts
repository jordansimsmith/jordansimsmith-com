import { describe, it, expect } from 'vitest';
import { createFakeClient } from './fake-client';

describe('fake-client', () => {
  const client = createFakeClient();

  it('returns no results for an empty query', async () => {
    const response = await client.search('');
    expect(response.results).toEqual([]);
  });

  it('returns no results for a whitespace-only query', async () => {
    const response = await client.search('   ');
    expect(response.results).toEqual([]);
  });

  it('returns at least one shaped result for shi', async () => {
    const response = await client.search('shi');
    expect(response.results.length).toBeGreaterThan(0);

    const first = response.results[0];
    expect(typeof first.sequence).toBe('number');
    expect(typeof first.expression).toBe('string');
    expect(typeof first.reading).toBe('string');
    expect(typeof first.reading_romaji).toBe('string');
    expect(first.glossary_raw).toBeDefined();
  });

  it('returns kana-prefix matches for しん', async () => {
    const response = await client.search('しん');
    expect(response.results.length).toBeGreaterThan(0);
    for (const result of response.results) {
      expect(result.reading.startsWith('しん')).toBe(true);
    }
  });

  it('returns kanji-prefix matches for 新', async () => {
    const response = await client.search('新');
    expect(response.results.length).toBeGreaterThan(0);
    for (const result of response.results) {
      expect(result.expression.startsWith('新')).toBe(true);
    }
  });

  it('caps results at 10', async () => {
    const response = await client.search('s');
    expect(response.results.length).toBeLessThanOrEqual(10);
  });

  it('sorts by frequency_rank ascending with nulls last', async () => {
    const response = await client.search('は');
    let sawNullRank = false;
    let lastRank: number | null = null;
    for (const result of response.results) {
      if (result.frequency_rank === null) {
        sawNullRank = true;
      } else {
        expect(sawNullRank).toBe(false);
        if (lastRank !== null) {
          expect(result.frequency_rank).toBeGreaterThanOrEqual(lastRank);
        }
        lastRank = result.frequency_rank;
      }
    }
  });
});
