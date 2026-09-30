import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearSession, setSession } from '../auth/session';
import { createHttpClient } from './http-client';

const fetchSpy = vi.fn();

describe('http client imports', () => {
  beforeEach(() => {
    fetchSpy.mockReset();
    globalThis.fetch = fetchSpy as unknown as typeof fetch;
    localStorage.clear();
    setSession('alice', 'pw');
  });

  afterEach(() => {
    clearSession();
  });

  it('gets authenticated game metadata', async () => {
    const response = {
      games: [
        {
          id: 'mtg',
          display_name: 'Magic: The Gathering',
          scanning_enabled: true,
          csv_import_enabled: true,
          scan_review_image_regions: [
            {
              id: 'set_code',
              display_name: 'Set code',
              x: 0,
              y: 0.9,
              width: 0.25,
              height: 0.1,
            },
            {
              id: 'set_symbol',
              display_name: 'Set symbol',
              x: 0.75,
              y: 0.535,
              width: 0.25,
              height: 0.1,
            },
          ],
          finishes: [
            { id: 'normal', display_name: 'Normal' },
            { id: 'foil', display_name: 'Foil' },
            { id: 'etched', display_name: 'Etched' },
          ],
        },
      ],
    };
    const json = vi.fn().mockResolvedValue(response);
    fetchSpy.mockResolvedValue({ ok: true, json });
    const client = createHttpClient();

    await expect(client.getGames()).resolves.toEqual(response);

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe('https://api.tcg-inventory.jordansimsmith.com/games');
    expect(init.method).toBeUndefined();
    expect(init.headers.Authorization).toBe(`Basic ${btoa('alice:pw')}`);
    expect(json).toHaveBeenCalledTimes(1);
  });

  it('sends the selected game and filename when creating an import', async () => {
    const json = vi.fn().mockResolvedValue({ import_id: 'import-1' });
    fetchSpy.mockResolvedValue({ ok: true, json });
    const client = createHttpClient();

    await client.createImport('mtg', 'bulk.csv', 'csv body');

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/imports?game=mtg&filename=bulk.csv',
    );
    expect(init.method).toBe('POST');
    expect(init.headers['Content-Type']).toBe('text/csv');
    expect(init.headers.Authorization).toBe(`Basic ${btoa('alice:pw')}`);
    expect(init.body).toBe('csv body');
    expect(json).toHaveBeenCalledTimes(1);
  });

  it('accepts an import without parsing a confirmation response body', async () => {
    const json = vi.fn();
    fetchSpy.mockResolvedValue({ ok: true, status: 202, json });

    await expect(
      createHttpClient().confirmImport('import-1'),
    ).resolves.toBeUndefined();

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/imports/import-1/confirm',
    );
    expect(init.method).toBe('POST');
    expect(init.headers.Authorization).toBe(`Basic ${btoa('alice:pw')}`);
    expect(json).not.toHaveBeenCalled();
  });
});

describe('http client catalog', () => {
  beforeEach(() => {
    fetchSpy.mockReset();
    globalThis.fetch = fetchSpy as unknown as typeof fetch;
    localStorage.clear();
    setSession('alice', 'pw');
  });

  afterEach(() => {
    clearSession();
  });

  it('gets exact catalog detail through the authenticated API', async () => {
    const card = {
      game: 'mtg',
      external_source: 'provider-id',
      external_id: 'opaque/id',
      name: 'Example',
      set_code: 'set',
      set_name: 'Set Name',
      collector_number: '12',
      image_urls: { small: null, normal: null },
      available_finishes: ['normal'],
    };
    const json = vi.fn().mockResolvedValue(card);
    fetchSpy.mockResolvedValue({ ok: true, json });

    await expect(
      createHttpClient().getCatalogCard('mtg', 'opaque/id'),
    ).resolves.toEqual(card);

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(fetchSpy.mock.calls[0][0]).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/catalog/cards/opaque%2Fid?game=mtg',
    );
    expect(fetchSpy.mock.calls[0][1].headers.Authorization).toBe(
      `Basic ${btoa('alice:pw')}`,
    );
  });

  it('encodes catalog alternatives continuations and search queries', async () => {
    const response = { cards: [], next_continuation: 'next/page' };
    const json = vi.fn().mockResolvedValue(response);
    fetchSpy.mockResolvedValue({ ok: true, json });
    const client = createHttpClient();

    await client.findCatalogAlternatives({
      game: 'mtg',
      external_id: 'opaque/id',
      finish: 'reverse holo',
      continuation: 'next/page',
    });
    await expect(
      client.findCatalogCards({
        game: 'mtg',
        query: 'Bolt & Co',
        finish: 'normal',
      }),
    ).resolves.toEqual(response);

    expect(fetchSpy.mock.calls[0][0]).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/catalog/cards/opaque%2Fid/alternatives?game=mtg&finish=reverse+holo&continuation=next%2Fpage',
    );
    expect(fetchSpy.mock.calls[1][0]).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/catalog/cards?game=mtg&query=Bolt+%26+Co&finish=normal',
    );
    expect(json).toHaveBeenCalledTimes(2);
  });

  it('surfaces catalog API errors', async () => {
    fetchSpy.mockResolvedValue({
      ok: false,
      statusText: 'Service Unavailable',
      json: vi
        .fn()
        .mockResolvedValue({ message: 'catalog is temporarily unavailable' }),
    });

    await expect(
      createHttpClient().findCatalogCards({
        game: 'mtg',
        query: 'Bolt',
        finish: 'normal',
      }),
    ).rejects.toThrow('catalog is temporarily unavailable');
  });
});

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

  it('confirms a scan without expecting a response body', async () => {
    const json = vi.fn();
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

    await expect(
      client.confirmScan('scan/1', request),
    ).resolves.toBeUndefined();

    expect(fetchSpy.mock.calls[0][0]).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/scans/scan%2F1/confirm',
    );
    expect(fetchSpy.mock.calls[0][1].method).toBe('POST');
    expect(fetchSpy.mock.calls[0][1].body).toBe(JSON.stringify(request));
    expect(json).not.toHaveBeenCalled();
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
