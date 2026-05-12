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

describe('fake-client bookmarks', () => {
  it('starts with no bookmarks', async () => {
    const fresh = createFakeClient();
    const response = await fresh.findBookmarks();
    expect(response.sequences).toEqual([]);
  });

  it('lists previously bookmarked sequences', async () => {
    const fresh = createFakeClient();
    await fresh.createBookmark(100);
    await fresh.createBookmark(200);

    const response = await fresh.findBookmarks();
    expect(response.sequences).toContain(100);
    expect(response.sequences).toContain(200);
  });

  it('returns bookmarks in created_at descending order (most recent first)', async () => {
    const fresh = createFakeClient();
    await fresh.createBookmark(11);
    await fresh.createBookmark(22);
    await fresh.createBookmark(33);

    const response = await fresh.findBookmarks();
    expect(response.sequences).toEqual([33, 22, 11]);
  });

  it('is idempotent on repeated createBookmark for the same sequence', async () => {
    const fresh = createFakeClient();
    await fresh.createBookmark(42);
    await fresh.createBookmark(42);

    const response = await fresh.findBookmarks();
    expect(response.sequences).toEqual([42]);
  });

  it('rejects non-positive sequences', async () => {
    const fresh = createFakeClient();
    await expect(fresh.createBookmark(0)).rejects.toThrow();
    await expect(fresh.createBookmark(-7)).rejects.toThrow();
  });

  it('removes a sequence from the listing on deleteBookmark', async () => {
    const fresh = createFakeClient();
    await fresh.createBookmark(11);
    await fresh.createBookmark(22);
    await fresh.deleteBookmark(11);

    const response = await fresh.findBookmarks();
    expect(response.sequences).toEqual([22]);
  });

  it('is idempotent on deleteBookmark for an unknown sequence', async () => {
    const fresh = createFakeClient();
    await fresh.deleteBookmark(999);

    const response = await fresh.findBookmarks();
    expect(response.sequences).toEqual([]);
  });

  it('rejects non-positive sequences on deleteBookmark', async () => {
    const fresh = createFakeClient();
    await expect(fresh.deleteBookmark(0)).rejects.toThrow();
    await expect(fresh.deleteBookmark(-7)).rejects.toThrow();
  });
});
