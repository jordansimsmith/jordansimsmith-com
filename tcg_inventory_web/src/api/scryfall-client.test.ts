import { afterEach, describe, expect, it, vi } from 'vitest';
import { createScryfallClient } from './scryfall-client';

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status });
}

function card(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'card-1',
    name: 'Example Card',
    set: 'set',
    set_name: 'Example Set',
    collector_number: '1',
    image_uris: { normal: 'https://img.example/card-1.jpg' },
    prints_search_uri: 'https://api.scryfall.com/cards/search?page=2',
    ...overrides,
  };
}

describe('scryfall client', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('retains the requested printing and follows every printing page', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response(card()))
      .mockResolvedValueOnce(
        response({
          data: [
            card({ id: 'card-1' }),
            card({ id: 'card-2', set: 'set-2', set_name: 'Second Set' }),
          ],
          has_more: true,
          next_page: 'https://api.scryfall.com/cards/search?page=3',
        }),
      )
      .mockResolvedValueOnce(
        response({
          data: [card({ id: 'card-3', set: 'set-3' })],
          has_more: false,
        }),
      );

    const printings =
      await createScryfallClient(fetchImpl).getPrintingsForId('card-1');

    expect(printings.map((printing) => printing.id)).toEqual([
      'card-1',
      'card-2',
      'card-3',
    ]);
    expect(fetchImpl).toHaveBeenNthCalledWith(
      3,
      'https://api.scryfall.com/cards/search?page=3',
      { signal: undefined },
    );
  });

  it('uses the first face image when a card has no top-level image', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValueOnce(
      response(
        card({
          id: 'dfc-1',
          name: 'Front // Back',
          image_uris: undefined,
          card_faces: [
            { image_uris: { normal: 'https://img.example/front.jpg' } },
            { image_uris: { normal: 'https://img.example/back.jpg' } },
          ],
          prints_search_uri: undefined,
        }),
      ),
    );

    const printings =
      await createScryfallClient(fetchImpl).getPrintingsForId('dfc-1');

    expect(printings[0]?.image_url).toBe('https://img.example/front.jpg');
  });

  it('reports a repeated pagination page instead of looping', async () => {
    const pageUrl = 'https://api.scryfall.com/cards/search?page=2';
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response(card()))
      .mockResolvedValue(
        response({
          data: [card({ id: 'card-2' })],
          has_more: true,
          next_page: pageUrl,
        }),
      );

    await expect(
      createScryfallClient(fetchImpl).getPrintingsForId('card-1'),
    ).rejects.toThrow('Scryfall pagination repeated a page');
  });

  it('reports an incomplete pagination response', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response(card()))
      .mockResolvedValueOnce(response({ data: [], has_more: true }));

    await expect(
      createScryfallClient(fetchImpl).getPrintingsForId('card-1'),
    ).rejects.toThrow('Scryfall pagination response is missing next_page');
  });

  it('searches exact names after autocomplete results are selected', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response({ data: ['Example Card'] }))
      .mockResolvedValueOnce(response(card({ prints_search_uri: undefined })));
    const client = createScryfallClient(fetchImpl);

    await expect(client.autocomplete('Example')).resolves.toEqual([
      'Example Card',
    ]);
    await expect(client.getPrintingsByName('Example Card')).resolves.toEqual([
      expect.objectContaining({ id: 'card-1', set_code: 'set' }),
    ]);
  });

  it('passes an abort signal to autocomplete and reports failures', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(response({}, 503));
    const controller = new AbortController();
    const client = createScryfallClient(fetchImpl);

    await expect(
      client.autocomplete('Example', controller.signal),
    ).rejects.toThrow('Scryfall request failed (503)');
    expect(fetchImpl).toHaveBeenCalledWith(
      'https://api.scryfall.com/cards/autocomplete?q=Example',
      { signal: controller.signal },
    );
  });

  it('fails loudly when a printing has no display image', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        response(card({ image_uris: undefined, prints_search_uri: undefined })),
      );

    await expect(
      createScryfallClient(fetchImpl).getPrintingsForId('card-1'),
    ).rejects.toThrow('has no display image');
  });
});
