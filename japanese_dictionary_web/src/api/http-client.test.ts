import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setSession, clearSession } from '../auth/session';
import { createHttpClient } from './http-client';

const fetchSpy = vi.fn();

describe('http client', () => {
  beforeEach(() => {
    fetchSpy.mockReset();
    globalThis.fetch = fetchSpy as unknown as typeof fetch;
    localStorage.clear();
  });

  afterEach(() => {
    clearSession();
  });

  it('builds the search URL with the encoded query and base URL fallback', async () => {
    setSession('alice', 'pw');
    fetchSpy.mockResolvedValue({
      ok: true,
      json: async () => ({ results: [] }),
    });

    const client = createHttpClient();
    await client.search('新');

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url] = fetchSpy.mock.calls[0];
    expect(url).toBe(
      'https://api.japanese-dictionary.jordansimsmith.com/search?q=' +
        encodeURIComponent('新'),
    );
  });

  it('sends the Basic Authorization header with the persisted session token', async () => {
    setSession('alice', 'pw');
    fetchSpy.mockResolvedValue({
      ok: true,
      json: async () => ({ results: [] }),
    });

    const client = createHttpClient();
    await client.search('shin');

    const [, init] = fetchSpy.mock.calls[0];
    expect(init.headers.Authorization).toBe(`Basic ${btoa('alice:pw')}`);
  });

  it('parses the JSON response body', async () => {
    setSession('alice', 'pw');
    const payload = {
      results: [
        {
          sequence: 1316830,
          expression: '新橋',
          reading: 'しんばし',
          reading_romaji: 'shinbashi',
          frequency_rank: 18472,
          pitch: 0,
          glossary_raw: { tag: 'div', content: 'Shinbashi' },
        },
      ],
    };
    fetchSpy.mockResolvedValue({ ok: true, json: async () => payload });

    const client = createHttpClient();
    const response = await client.search('shinbashi');

    expect(response).toEqual(payload);
  });

  it('throws an error with the server-provided message on a non-2xx response', async () => {
    setSession('alice', 'pw');
    fetchSpy.mockResolvedValue({
      ok: false,
      statusText: 'Bad Request',
      json: async () => ({ message: 'q too long' }),
    });

    const client = createHttpClient();

    await expect(client.search('x'.repeat(100))).rejects.toThrow('q too long');
  });

  it('falls back to the status text when the error body is not JSON', async () => {
    setSession('alice', 'pw');
    fetchSpy.mockResolvedValue({
      ok: false,
      statusText: 'Internal Server Error',
      json: async () => {
        throw new Error('not json');
      },
    });

    const client = createHttpClient();

    await expect(client.search('x')).rejects.toThrow(
      'Request failed: Internal Server Error',
    );
  });

  it('throws when the user is not authenticated', async () => {
    const client = createHttpClient();

    await expect(client.search('shin')).rejects.toThrow('Not authenticated');
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('fetches the bookmarks list with auth and maps the wire shape to sequence ids', async () => {
    setSession('alice', 'pw');
    fetchSpy.mockResolvedValue({
      ok: true,
      json: async () => ({
        bookmarks: [
          {
            sequence: 1,
            created_at: 1700000000,
            expression: null,
            reading: null,
            reading_romaji: null,
            frequency_rank: null,
            pitch: null,
            glossary_raw: null,
          },
          {
            sequence: 2,
            created_at: 1700000001,
            expression: null,
            reading: null,
            reading_romaji: null,
            frequency_rank: null,
            pitch: null,
            glossary_raw: null,
          },
          {
            sequence: 3,
            created_at: 1700000002,
            expression: null,
            reading: null,
            reading_romaji: null,
            frequency_rank: null,
            pitch: null,
            glossary_raw: null,
          },
        ],
      }),
    });

    const client = createHttpClient();
    const response = await client.findBookmarks();

    expect(response).toEqual({ sequences: [1, 2, 3] });
    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe(
      'https://api.japanese-dictionary.jordansimsmith.com/bookmarks',
    );
    expect(init.headers.Authorization).toBe(`Basic ${btoa('alice:pw')}`);
  });

  it('issues PUT for createBookmark with the encoded sequence in the path', async () => {
    setSession('alice', 'pw');
    fetchSpy.mockResolvedValue({
      ok: true,
      json: async () => ({ sequence: 1316830, created_at: 1700000000 }),
    });

    const client = createHttpClient();
    await client.createBookmark(1316830);

    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe(
      'https://api.japanese-dictionary.jordansimsmith.com/bookmarks/1316830',
    );
    expect(init.method).toBe('PUT');
    expect(init.headers.Authorization).toBe(`Basic ${btoa('alice:pw')}`);
  });

  it('throws createBookmark with the server-provided message on a non-2xx response', async () => {
    setSession('alice', 'pw');
    fetchSpy.mockResolvedValue({
      ok: false,
      statusText: 'Bad Request',
      json: async () => ({ message: 'sequence must be a positive integer' }),
    });

    const client = createHttpClient();

    await expect(client.createBookmark(-1)).rejects.toThrow(
      'sequence must be a positive integer',
    );
  });

  it('issues DELETE for deleteBookmark with the encoded sequence in the path', async () => {
    setSession('alice', 'pw');
    fetchSpy.mockResolvedValue({
      ok: true,
      json: async () => ({}),
    });

    const client = createHttpClient();
    await client.deleteBookmark(1316830);

    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe(
      'https://api.japanese-dictionary.jordansimsmith.com/bookmarks/1316830',
    );
    expect(init.method).toBe('DELETE');
    expect(init.headers.Authorization).toBe(`Basic ${btoa('alice:pw')}`);
  });

  it('throws deleteBookmark with the server-provided message on a non-2xx response', async () => {
    setSession('alice', 'pw');
    fetchSpy.mockResolvedValue({
      ok: false,
      statusText: 'Bad Request',
      json: async () => ({ message: 'sequence must be a positive integer' }),
    });

    const client = createHttpClient();

    await expect(client.deleteBookmark(-1)).rejects.toThrow(
      'sequence must be a positive integer',
    );
  });
});
