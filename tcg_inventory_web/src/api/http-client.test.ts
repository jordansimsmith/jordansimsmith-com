import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearSession, setSession } from '../auth/session';
import { createHttpClient } from './http-client';

const fetchSpy = vi.fn();

describe('http client settings', () => {
  beforeEach(() => {
    fetchSpy.mockReset();
    globalThis.fetch = fetchSpy as unknown as typeof fetch;
    localStorage.clear();
    setSession('alice', 'pw');
  });

  afterEach(() => {
    clearSession();
  });

  it('gets settings and updates only the order-tracking cutoff', async () => {
    const settings = { track_orders_after: 1723363200 };
    fetchSpy
      .mockResolvedValueOnce({
        ok: true,
        json: vi.fn().mockResolvedValue(settings),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: vi.fn().mockResolvedValue(settings),
      });
    const client = createHttpClient();

    await expect(client.getSettings()).resolves.toEqual(settings);
    await expect(
      client.updateSettings({ track_orders_after: 1723363200 }),
    ).resolves.toEqual(settings);

    expect(fetchSpy).toHaveBeenCalledTimes(2);
    expect(fetchSpy.mock.calls[0][0]).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/settings',
    );
    const [url, init] = fetchSpy.mock.calls[1];
    expect(url).toBe('https://api.tcg-inventory.jordansimsmith.com/settings');
    expect(init.method).toBe('PATCH');
    expect(init.headers['Content-Type']).toBe('application/json');
    expect(init.body).toBe(JSON.stringify({ track_orders_after: 1723363200 }));
  });
});

describe('http client FetchTCG account', () => {
  beforeEach(() => {
    fetchSpy.mockReset();
    globalThis.fetch = fetchSpy as unknown as typeof fetch;
    localStorage.clear();
    setSession('alice', 'pw');
  });

  afterEach(() => {
    clearSession();
  });

  it('gets account metadata and maps only not found to null', async () => {
    const account = {
      seller_username: 'verified-seller',
      credentials_updated_at: 1700000000,
    };
    fetchSpy
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: vi.fn().mockResolvedValue(account),
      })
      .mockResolvedValueOnce({
        ok: false,
        status: 404,
        statusText: 'Not Found',
        json: vi.fn(),
      })
      .mockResolvedValueOnce({
        ok: false,
        status: 503,
        statusText: 'Service Unavailable',
        json: vi.fn().mockResolvedValue({ message: 'temporarily unavailable' }),
      });
    const client = createHttpClient();

    await expect(client.getFetchTcgAccount()).resolves.toEqual(account);
    await expect(client.getFetchTcgAccount()).resolves.toBeNull();
    await expect(client.getFetchTcgAccount()).rejects.toThrow(
      'temporarily unavailable',
    );

    expect(fetchSpy).toHaveBeenCalledTimes(3);
    expect(fetchSpy.mock.calls[0][0]).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/fetchtcg/account',
    );
    expect(fetchSpy.mock.calls[0][1].method).toBeUndefined();
    expect(fetchSpy.mock.calls[0][1].body).toBeUndefined();
    expect(fetchSpy.mock.calls[0][1].headers.Authorization).toBe(
      `Basic ${btoa('alice:pw')}`,
    );
  });

  it('puts credentials without changing the token and returns account metadata', async () => {
    const token = ' refresh token with spaces ';
    const response = {
      seller_username: 'verified-seller',
      credentials_updated_at: 1700000000,
    };
    fetchSpy.mockResolvedValue({
      ok: true,
      status: 200,
      json: vi.fn().mockResolvedValue(response),
    });

    await expect(
      createHttpClient().updateFetchTcgCredentials({ refresh_token: token }),
    ).resolves.toEqual(response);

    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/fetchtcg/credentials',
    );
    expect(init.method).toBe('PUT');
    expect(init.headers['Content-Type']).toBe('application/json');
    expect(init.headers.Authorization).toBe(`Basic ${btoa('alice:pw')}`);
    expect(init.body).toBe(JSON.stringify({ refresh_token: token }));
  });
});

describe('http client pricing', () => {
  beforeEach(() => {
    fetchSpy.mockReset();
    globalThis.fetch = fetchSpy as unknown as typeof fetch;
    localStorage.clear();
    setSession('alice', 'pw');
  });

  afterEach(() => {
    clearSession();
  });

  it('gets and updates the authenticated price floor using decimal strings', async () => {
    const getResponse = { price_floor: '0.25' };
    const updateResponse = { price_floor: '1.20' };
    const getJson = vi.fn().mockResolvedValue(getResponse);
    const updateJson = vi.fn().mockResolvedValue(updateResponse);
    fetchSpy
      .mockResolvedValueOnce({ ok: true, json: getJson })
      .mockResolvedValueOnce({ ok: true, json: updateJson });
    const client = createHttpClient();

    await expect(client.getPricing()).resolves.toEqual(getResponse);
    await expect(client.updatePricing({ price_floor: '1.2' })).resolves.toEqual(
      updateResponse,
    );

    expect(fetchSpy).toHaveBeenCalledTimes(2);
    expect(fetchSpy.mock.calls[0][0]).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/pricing',
    );
    expect(fetchSpy.mock.calls[0][1].method).toBeUndefined();
    expect(fetchSpy.mock.calls[0][1].headers.Authorization).toBe(
      `Basic ${btoa('alice:pw')}`,
    );
    const [url, init] = fetchSpy.mock.calls[1];
    expect(url).toBe('https://api.tcg-inventory.jordansimsmith.com/pricing');
    expect(init.method).toBe('PATCH');
    expect(init.headers['Content-Type']).toBe('application/json');
    expect(init.headers.Authorization).toBe(`Basic ${btoa('alice:pw')}`);
    expect(init.body).toBe(JSON.stringify({ price_floor: '1.2' }));
    expect(getJson).toHaveBeenCalledTimes(1);
    expect(updateJson).toHaveBeenCalledTimes(1);
  });

  it('surfaces pricing API errors', async () => {
    fetchSpy.mockResolvedValue({
      ok: false,
      status: 409,
      statusText: 'Conflict',
      json: vi
        .fn()
        .mockResolvedValue({ message: 'another job is in progress' }),
    });

    await expect(
      createHttpClient().updatePricing({ price_floor: '1.20' }),
    ).rejects.toThrow('another job is in progress');
  });
});

describe('http client repricing', () => {
  beforeEach(() => {
    fetchSpy.mockReset();
    globalThis.fetch = fetchSpy as unknown as typeof fetch;
    localStorage.clear();
    setSession('alice', 'pw');
  });

  afterEach(() => {
    clearSession();
  });

  it('starts repricing with an authenticated bodyless request', async () => {
    const json = vi.fn();
    fetchSpy.mockResolvedValue({ ok: true, status: 202, json });

    await expect(createHttpClient().createReprice()).resolves.toBeUndefined();

    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/pricing/reprice',
    );
    expect(init.method).toBe('POST');
    expect(init.headers.Authorization).toBe(`Basic ${btoa('alice:pw')}`);
    expect(init.body).toBeUndefined();
    expect(json).not.toHaveBeenCalled();
  });

  it('returns null when no repricing run exists', async () => {
    fetchSpy.mockResolvedValue({
      ok: false,
      status: 404,
      statusText: 'Not Found',
      json: vi.fn(),
    });

    await expect(createHttpClient().getReprice()).resolves.toBeNull();
    expect(fetchSpy.mock.calls[0][0]).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/pricing/reprice',
    );
  });

  it('returns the latest repricing status and surfaces other errors', async () => {
    const response = {
      job_id: 'job-1',
      status: 'running',
      processed_sku_count: 100,
      error: null,
      started_at: 1723363200,
      finished_at: null,
    };
    fetchSpy.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: vi.fn().mockResolvedValue(response),
    });
    await expect(createHttpClient().getReprice()).resolves.toEqual(response);

    fetchSpy.mockResolvedValueOnce({
      ok: false,
      status: 503,
      statusText: 'Service Unavailable',
      json: vi.fn().mockResolvedValue({ message: 'temporarily unavailable' }),
    });
    await expect(createHttpClient().getReprice()).rejects.toThrow(
      'temporarily unavailable',
    );
  });

  it('surfaces a conflict when another job is active', async () => {
    fetchSpy.mockResolvedValue({
      ok: false,
      status: 409,
      statusText: 'Conflict',
      json: vi
        .fn()
        .mockResolvedValue({ message: 'another job is in progress' }),
    });

    await expect(createHttpClient().createReprice()).rejects.toThrow(
      'another job is in progress',
    );
  });
});

describe('http client inventory units', () => {
  beforeEach(() => {
    fetchSpy.mockReset();
    globalThis.fetch = fetchSpy as unknown as typeof fetch;
    localStorage.clear();
    setSession('alice', 'pw');
  });

  afterEach(() => {
    clearSession();
  });

  it('finds authenticated unit pages with encoded SKU and pagination parameters', async () => {
    const responseBody = {
      units: [
        {
          sequence_number: 3,
          location: 'A0-3',
          status: 'in_stock',
          photos: ['photo-1'],
          created_at: 1700000000,
          updated_at: null,
        },
      ],
      next_continuation: 'opaque-next',
    };
    fetchSpy.mockResolvedValue({
      ok: true,
      json: vi.fn().mockResolvedValue(responseBody),
    });

    await expect(
      createHttpClient().findUnits({
        sku_id: 'set/id #NM',
        limit: 25,
        continuation: 'opaque+/=',
      }),
    ).resolves.toEqual(responseBody);

    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/skus/set%2Fid%20%23NM/units?limit=25&continuation=opaque%2B%2F%3D',
    );
    expect(init.headers.Authorization).toBe(`Basic ${btoa('alice:pw')}`);
  });

  it('surfaces unit listing API errors', async () => {
    fetchSpy.mockResolvedValue({
      ok: false,
      status: 503,
      statusText: 'Unavailable',
      json: vi.fn().mockResolvedValue({ message: 'inventory is unavailable' }),
    });

    await expect(
      createHttpClient().findUnits({ sku_id: 'sku-1' }),
    ).rejects.toThrow('inventory is unavailable');
  });
});

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

  it('finds card images with repeated encoded IDs through the authenticated API', async () => {
    const result = {
      images: [
        {
          external_id: 'opaque/id & 2',
          image_urls: { small: null, normal: 'https://image.test/card' },
        },
      ],
    };
    const json = vi.fn().mockResolvedValue(result);
    fetchSpy.mockResolvedValue({ ok: true, json });

    await expect(
      createHttpClient().findCardImages({
        game: 'mtg',
        external_ids: ['opaque/id', 'opaque/id & 2'],
      }),
    ).resolves.toEqual(result);

    expect(fetchSpy.mock.calls[0][0]).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/catalog/cardimages?game=mtg&external_ids=opaque%2Fid&external_ids=opaque%2Fid+%26+2',
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

    await client.findSkus({ game: 'mtg', search: 'sol ring', limit: 12 });

    expect(fetchSpy.mock.calls[0][0]).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/skus?game=mtg&search=sol+ring&limit=12',
    );
  });

  it('gets a wrapped SKU with numeric price from the encoded detail path', async () => {
    const responseBody = {
      sku: {
        sku_id: 'sku#opaque',
        game: 'mtg',
        external_id: 'opaque/id',
        name: 'Card',
        set_code: 'set',
        set_name: 'Set',
        collector_number: '1',
        finish: 'normal',
        condition: 'NM',
        last_published_price: 0.3,
      },
    };
    fetchSpy.mockResolvedValue({
      ok: true,
      json: vi.fn().mockResolvedValue(responseBody),
    });

    await expect(createHttpClient().getSku('sku#opaque')).resolves.toEqual(
      responseBody,
    );

    expect(fetchSpy.mock.calls[0][0]).toBe(
      'https://api.tcg-inventory.jordansimsmith.com/skus/sku%23opaque',
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
