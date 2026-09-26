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

describe('http client scans', () => {
  beforeEach(() => {
    fetchSpy.mockReset();
    globalThis.fetch = fetchSpy as unknown as typeof fetch;
    localStorage.clear();
    setSession('alice', 'pw');
  });

  afterEach(() => {
    clearSession();
  });

  it('creates a scan with JSON metadata', async () => {
    const json = vi.fn().mockResolvedValue({ scan_id: 'scan-1', rows: [] });
    fetchSpy.mockResolvedValue({ ok: true, json });
    const client = createHttpClient();

    await client.createScan({
      game: 'mtg',
      condition: 'LP',
      finish: 'foil',
      files: [{ filename: '001.jpg', size_bytes: 42 }],
    });

    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe('https://api.tcg-inventory.jordansimsmith.com/scans');
    expect(init.method).toBe('POST');
    expect(init.headers['Content-Type']).toBe('application/json');
    expect(init.headers.Authorization).toBe(`Basic ${btoa('alice:pw')}`);
    expect(init.body).toBe(
      JSON.stringify({
        game: 'mtg',
        condition: 'LP',
        finish: 'foil',
        files: [{ filename: '001.jpg', size_bytes: 42 }],
      }),
    );
  });

  it('lists scans with continuation', async () => {
    const json = vi.fn().mockResolvedValue({ scans: [] });
    fetchSpy.mockResolvedValue({ ok: true, json });
    const client = createHttpClient();

    await client.findScans({ continuation: 'page/2' });

    expect(fetchSpy.mock.calls[0][0]).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/scans?continuation=page%2F2',
    );
  });

  it('gets and identifies an encoded scan', async () => {
    const json = vi.fn().mockResolvedValue({ scan_id: 'scan/1' });
    fetchSpy.mockResolvedValue({ ok: true, json });
    const client = createHttpClient();

    await client.getScan('scan/1');
    await client.identifyScan('scan/1');

    expect(fetchSpy.mock.calls[0][0]).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/scans/scan%2F1',
    );
    expect(fetchSpy.mock.calls[1][0]).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/scans/scan%2F1/identify',
    );
    expect(fetchSpy.mock.calls[1][1].method).toBe('POST');
  });

  it('deletes a scan row without parsing a body', async () => {
    const json = vi.fn();
    fetchSpy.mockResolvedValue({ ok: true, json });
    const client = createHttpClient();

    await client.deleteScanRow('scan/1', 7);

    expect(fetchSpy.mock.calls[0][0]).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/scans/scan%2F1/rows/7',
    );
    expect(fetchSpy.mock.calls[0][1].method).toBe('DELETE');
    expect(json).not.toHaveBeenCalled();
  });

  it('confirms a scan with the selected printing rows', async () => {
    const json = vi.fn().mockResolvedValue({ import_id: 'import-1' });
    fetchSpy.mockResolvedValue({ ok: true, json });
    const client = createHttpClient();
    const request = {
      rows: [
        {
          scan_position: 1,
          external_source: 'scryfall',
          external_id: 'card-1',
          name: 'Opt',
          set_code: 'dom',
          set_name: 'Dominaria',
          collector_number: '60',
        },
      ],
    };

    await client.confirmScan('scan/1', request);

    expect(fetchSpy.mock.calls[0][0]).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/scans/scan%2F1/confirm',
    );
    expect(fetchSpy.mock.calls[0][1].method).toBe('POST');
    expect(fetchSpy.mock.calls[0][1].body).toBe(JSON.stringify(request));
  });

  it('requires a game when listing SKUs', async () => {
    const json = vi
      .fn()
      .mockResolvedValue({ skus: [], next_continuation: null });
    fetchSpy.mockResolvedValue({ ok: true, json });
    const client = createHttpClient();

    await client.findSkus({ game: 'mtg', search: 'sol ring' });

    expect(fetchSpy.mock.calls[0][0]).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/skus?game=mtg&search=sol+ring',
    );
  });

  it('deletes a scan without parsing a body', async () => {
    const json = vi.fn();
    fetchSpy.mockResolvedValue({ ok: true, json });
    const client = createHttpClient();

    await client.deleteScan('scan/1');

    expect(fetchSpy.mock.calls[0][0]).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/scans/scan%2F1',
    );
    expect(fetchSpy.mock.calls[0][1].method).toBe('DELETE');
    expect(json).not.toHaveBeenCalled();
  });
});
