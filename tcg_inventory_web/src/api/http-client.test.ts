import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearSession, setSession } from '../auth/session';
import { createHttpClient } from './http-client';

const fetchSpy = vi.fn();

describe('http client row photos', () => {
  beforeEach(() => {
    fetchSpy.mockReset();
    globalThis.fetch = fetchSpy as unknown as typeof fetch;
    localStorage.clear();
  });

  afterEach(() => {
    clearSession();
  });

  it('posts a raw image/jpeg body when adding a row photo', async () => {
    setSession('alice', 'pw');
    const json = vi.fn();
    fetchSpy.mockResolvedValue({ ok: true, json });
    const jpeg = new Blob([new Uint8Array([0xff, 0xd8, 0xff])], {
      type: 'image/jpeg',
    });

    const client = createHttpClient();
    await client.addRowPhoto('imp/1', 3, jpeg);

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/imports/imp%2F1/rows/3/photos',
    );
    expect(init.method).toBe('POST');
    expect(init.headers['Content-Type']).toBe('image/jpeg');
    expect(init.headers.Authorization).toBe(`Basic ${btoa('alice:pw')}`);
    expect(init.body).toBe(jpeg);
    expect(json).not.toHaveBeenCalled();
  });

  it('deletes a row photo without parsing a body', async () => {
    setSession('alice', 'pw');
    const json = vi.fn();
    fetchSpy.mockResolvedValue({ ok: true, json });

    const client = createHttpClient();
    await client.deleteRowPhoto('imp/1', 3, 'photo/9');

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/imports/imp%2F1/rows/3/photos/photo%2F9',
    );
    expect(init.method).toBe('DELETE');
    expect(json).not.toHaveBeenCalled();
  });
});
