import { afterEach, describe, it, expect, vi } from 'vitest';
import { createFakeClient } from './fake-client';

async function findSkuId(
  client: ReturnType<typeof createFakeClient>,
  search: string,
  index = 0,
): Promise<string> {
  const response = await client.findSkus({ search });
  return response.skus[index].sku_id;
}

describe('createFakeClient', () => {
  it('derives detail counts from units', async () => {
    const client = createFakeClient();
    const response = await client.findSkus({ search: 'sol ring' });
    const nmSku = response.skus.find((sku) => sku.condition === 'NM');

    const detail = await client.getSku(nmSku!.sku_id);

    expect(detail.in_stock_count).toBe(6);
    expect(detail.reserved_count).toBe(2);
  });

  it('returns units ascending with derived locations', async () => {
    const client = createFakeClient();
    const skuId = await findSkuId(client, 'sol ring', 1);

    const detail = await client.getSku(skuId);

    expect(detail.condition).toBe('NM');
    expect(detail.units).toHaveLength(8);
    const sequenceNumbers = detail.units.map((unit) => unit.sequence_number);
    expect(sequenceNumbers).toEqual([...sequenceNumbers].sort((a, b) => a - b));
    for (const unit of detail.units) {
      const block = Math.floor(unit.sequence_number / 100);
      expect(unit.location).toBe(`A${block}-${unit.sequence_number % 100}`);
    }
    expect(detail.units[0].status).toBe('reserved');
    expect(detail.units[1].status).toBe('reserved');
  });

  it('seeds a photographed Doubling Season unit', async () => {
    const client = createFakeClient();
    const skuId = await findSkuId(client, 'doubling season');
    const detail = await client.getSku(skuId);

    expect(detail.units).toHaveLength(1);
    expect(detail.units[0].photos).toEqual([
      {
        photo_id: 'fake-unit-photo-1',
        url: expect.stringMatching(/^data:image\/svg\+xml,/),
      },
    ]);

    const solRingId = await findSkuId(client, 'sol ring', 1);
    const solRing = await client.getSku(solRingId);
    expect(solRing.units.every((unit) => unit.photos.length === 0)).toBe(true);
  });

  it('rejects unknown SKUs', async () => {
    const client = createFakeClient();

    await expect(client.getSku('missing#normal#NM')).rejects.toThrow(
      'Not Found',
    );
  });

  it('marks a unit removed and updates counts on deleteUnit', async () => {
    const client = createFakeClient();
    const skuId = await findSkuId(client, 'brainstorm');
    const detail = await client.getSku(skuId);
    const unit = detail.units.find((entry) => entry.status === 'in_stock');

    await client.deleteUnit(skuId, unit!.sequence_number, 'damaged in storage');

    const updated = await client.getSku(skuId);
    expect(updated.in_stock_count).toBe(detail.in_stock_count - 1);
    expect(
      updated.units.find(
        (entry) => entry.sequence_number === unit!.sequence_number,
      )?.status,
    ).toBe('removed');
  });

  it('rejects deleteUnit for units that are not in stock', async () => {
    const client = createFakeClient();
    const skuId = await findSkuId(client, 'mana crypt');
    const detail = await client.getSku(skuId);

    expect(detail.units[0].status).toBe('reserved');
    await expect(
      client.deleteUnit(skuId, detail.units[0].sequence_number),
    ).rejects.toThrow('unit is not in stock');
  });

  it('moves a unit to an existing SKU on updateUnit', async () => {
    const client = createFakeClient();
    const sourceId = await findSkuId(client, 'lightning bolt', 1);
    const source = await client.getSku(sourceId);
    expect(source.condition).toBe('NM');
    const unit = source.units.find((entry) => entry.status === 'in_stock');

    const response = await client.updateUnit(
      sourceId,
      unit!.sequence_number,
      'LP',
    );

    const targetId = await findSkuId(client, 'lightning bolt', 0);
    expect(response.sku_id).toBe(targetId);
    const target = await client.getSku(targetId);
    expect(target.condition).toBe('LP');
    expect(target.in_stock_count).toBe(3);
    expect(
      target.units.find(
        (entry) => entry.sequence_number === unit!.sequence_number,
      )?.status,
    ).toBe('in_stock');

    const updatedSource = await client.getSku(sourceId);
    expect(updatedSource.in_stock_count).toBe(source.in_stock_count - 1);
  });

  it('creates the target SKU on updateUnit when it does not exist', async () => {
    const client = createFakeClient();
    const sourceId = await findSkuId(client, 'sylvan library');
    const source = await client.getSku(sourceId);
    const unit = source.units[0];

    const response = await client.updateUnit(
      sourceId,
      unit.sequence_number,
      'DMG',
    );

    expect(response.sku_id).toBe(`${source.scryfall_id}#normal#DMG`);
    const target = await client.getSku(response.sku_id);
    expect(target.name).toBe('Sylvan Library');
    expect(target.condition).toBe('DMG');
    expect(target.in_stock_count).toBe(1);
    expect(target.units[0].sequence_number).toBe(unit.sequence_number);

    const browse = await client.findSkus({ search: 'sylvan library' });
    expect(browse.skus).toHaveLength(2);
  });
});

const MANABOX_HEADER =
  'Name,Set code,Set name,Collector number,Foil,Rarity,Quantity,Scryfall ID,Misprint,Altered,Condition,Language';

const SAMPLE_CSV = [
  MANABOX_HEADER,
  'Llanowar Elves,dom,Dominaria,168,normal,common,1,581b7327-3215-4a4f-b4ae-d9d4002ba882,false,false,near_mint,en',
  'Opt,dom,Dominaria,60,normal,common,2,25f2e4d0-effd-4e83-b7aa-1a0d8f120951,false,false,excellent,en',
  'Ponder,m12,Magic 2012,73,normal,common,1,81c908ee-e70a-4406-a32d-ab5ab17e67b1,false,false,good,ja',
].join('\n');

describe('createFakeClient imports', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('seeds imports newest-first with an in-flight appraisal', async () => {
    const client = createFakeClient();

    const response = await client.findImports();

    expect(response.imports).toHaveLength(3);
    const [inFlight, review, confirmed] = response.imports;
    expect(inFlight.status).toBe('appraising');
    expect(inFlight.created_at).toBeGreaterThan(review.created_at);
    expect(review.status).toBe('review');
    expect(review.created_at).toBeGreaterThan(confirmed.created_at);
    expect(inFlight.row_count).toBeGreaterThan(0);
    expect(confirmed.status).toBe('confirmed');
  });

  it('creates an import with quantity-expanded rows top-of-stack first', async () => {
    vi.useFakeTimers();
    const client = createFakeClient();

    const created = await client.createImport('bulk.csv', SAMPLE_CSV);

    expect(created.status).toBe('appraising');
    expect(created.filename).toBe('bulk.csv');
    expect(created.row_count).toBe(4);

    const detail = await client.getImport(created.import_id);
    expect(detail.rows.map((row) => row.position)).toEqual([1, 2, 3, 4]);
    expect(detail.rows.map((row) => row.name)).toEqual([
      'Ponder',
      'Opt',
      'Opt',
      'Llanowar Elves',
    ]);
    expect(detail.rows[0].condition).toBe('MP');
    expect(detail.rows[1].condition).toBe('LP');
    expect(detail.rows[0].decision).toBeNull();
    expect(detail.rows[0].decision_reason).toBeNull();
  });

  it('progresses an uploaded import to review over time', async () => {
    vi.useFakeTimers();
    const client = createFakeClient();
    const created = await client.createImport('bulk.csv', SAMPLE_CSV);

    vi.advanceTimersByTime(1000);
    let detail = await client.getImport(created.import_id);
    expect(detail.status).toBe('appraising');
    expect(detail.rows.filter((r) => r.decision !== null)).toHaveLength(2);

    vi.advanceTimersByTime(60_000);
    detail = await client.getImport(created.import_id);
    expect(detail.status).toBe('review');
    expect(detail.rows.filter((r) => r.decision === 'keep')).toHaveLength(3);
    expect(detail.rows.filter((r) => r.decision === 'review')).toHaveLength(1);
    expect(detail.rows[0].decision).toBe('review');
    expect(detail.rows[0].decision_reason).toBe('non-English card');
    expect(detail.rows[3].decision).toBe('keep');

    const list = await client.findImports();
    expect(list.imports[0].import_id).toBe(created.import_id);
    expect(list.imports[0].status).toBe('review');
  });

  it('completes the seeded in-flight appraisal over time', async () => {
    vi.useFakeTimers();
    const client = createFakeClient();

    vi.advanceTimersByTime(60_000);

    const response = await client.findImports();
    expect(response.imports[0].status).toBe('review');
  });

  it('rejects invalid csv content', async () => {
    const client = createFakeClient();

    await expect(
      client.createImport('bad.csv', 'Name,Quantity\nOpt,1'),
    ).rejects.toThrow('CSV is missing columns');
  });

  it('rejects unknown import ids', async () => {
    const client = createFakeClient();

    await expect(client.getImport('missing')).rejects.toThrow('Not Found');
  });

  it('appraises prices for keep and discard rows', async () => {
    vi.useFakeTimers();
    const client = createFakeClient();
    const created = await client.createImport('bulk.csv', SAMPLE_CSV);

    let detail = await client.getImport(created.import_id);
    expect(detail.rows[3].market_price).toBeNull();

    vi.advanceTimersByTime(60_000);
    detail = await client.getImport(created.import_id);
    expect(detail.rows[0].decision).toBe('review');
    expect(detail.rows[0].market_price).toBeNull();
    expect(detail.rows[0].suggested_price).toBeNull();
    expect(detail.rows[3].decision).toBe('keep');
    expect(detail.rows[3].market_price).toMatch(/^\d+\.\d{2}$/);
    expect(detail.rows[3].suggested_price).toMatch(/^\d+\.\d{2}$/);

    const confirmedSeed = await client.getImport('fake-import-1');
    const discardRow = confirmedSeed.rows.find(
      (row) => row.decision === 'discard',
    );
    expect(Number(discardRow?.market_price)).toBeLessThan(0.25);
    expect(discardRow?.suggested_price).toBeNull();
  });

  it('rejects confirm unless the import is in review', async () => {
    vi.useFakeTimers();
    const client = createFakeClient();
    const created = await client.createImport('bulk.csv', SAMPLE_CSV);

    await expect(client.confirmImport(created.import_id)).rejects.toThrow(
      'import is not in review status',
    );

    vi.advanceTimersByTime(60_000);
    await client.confirmImport(created.import_id);
    await expect(client.confirmImport(created.import_id)).rejects.toThrow(
      'import is not in review status',
    );
  });

  it('confirms keep rows bottom-up into inventory and skips review rows', async () => {
    vi.useFakeTimers();
    const client = createFakeClient();
    const created = await client.createImport('bulk.csv', SAMPLE_CSV);
    vi.advanceTimersByTime(60_000);

    const response = await client.confirmImport(created.import_id);

    expect(response.import_id).toBe(created.import_id);
    expect(response.status).toBe('confirmed');
    expect(response.unit_count).toBe(3);
    expect(response.first_sequence_number).toBe(600);
    expect(response.last_sequence_number).toBe(602);
    expect(response.placement_instructions).toEqual([
      {
        block: 'A6',
        from_location: 'A6-0',
        to_location: 'A6-2',
        from_name: 'Llanowar Elves',
        to_name: 'Opt',
        unit_count: 3,
      },
    ]);

    // the stack bottom (llanowar elves, csv row 1) gets the first sequence number
    const elves = await client.getSku(
      '581b7327-3215-4a4f-b4ae-d9d4002ba882#normal#NM',
    );
    expect(elves.in_stock_count).toBe(7);
    expect(elves.units.map((unit) => unit.sequence_number)).toContain(600);

    // the lp opt sku did not exist and is created by the confirm
    const opt = await client.getSku(
      '25f2e4d0-effd-4e83-b7aa-1a0d8f120951#normal#LP',
    );
    expect(opt.in_stock_count).toBe(2);
    expect(opt.units.map((unit) => unit.sequence_number)).toEqual([601, 602]);

    // the review row (non-english ponder) never becomes a unit
    await expect(
      client.getSku('81c908ee-e70a-4406-a32d-ab5ab17e67b1#normal#MP'),
    ).rejects.toThrow('Not Found');

    const detail = await client.getImport(created.import_id);
    expect(detail.status).toBe('confirmed');
  });

  it('splits placement instructions at block boundaries', async () => {
    vi.useFakeTimers();
    const client = createFakeClient();
    const csv = [
      MANABOX_HEADER,
      'Relentless Rats,8ed,Eighth Edition,151,normal,uncommon,130,aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa,false,false,near_mint,en',
    ].join('\n');
    const created = await client.createImport('rats.csv', csv);
    vi.advanceTimersByTime(120_000);

    const response = await client.confirmImport(created.import_id);

    // 130 rows with every 5th discarded leaves 104 keeps spanning two blocks
    expect(response.unit_count).toBe(104);
    expect(response.first_sequence_number).toBe(600);
    expect(response.last_sequence_number).toBe(703);
    expect(response.placement_instructions).toEqual([
      {
        block: 'A6',
        from_location: 'A6-0',
        to_location: 'A6-99',
        from_name: 'Relentless Rats',
        to_name: 'Relentless Rats',
        unit_count: 100,
      },
      {
        block: 'A7',
        from_location: 'A7-0',
        to_location: 'A7-3',
        from_name: 'Relentless Rats',
        to_name: 'Relentless Rats',
        unit_count: 4,
      },
    ]);
  });

  it('confirms an import with no keep rows without placements', async () => {
    vi.useFakeTimers();
    const client = createFakeClient();
    const csv = [
      MANABOX_HEADER,
      'Ponder,m12,Magic 2012,73,normal,common,1,81c908ee-e70a-4406-a32d-ab5ab17e67b1,false,false,good,ja',
    ].join('\n');
    const created = await client.createImport('review-only.csv', csv);
    vi.advanceTimersByTime(10_000);

    const response = await client.confirmImport(created.import_id);

    expect(response.unit_count).toBe(0);
    expect(response.first_sequence_number).toBeNull();
    expect(response.last_sequence_number).toBeNull();
    expect(response.placement_instructions).toEqual([]);
  });
});

describe('createFakeClient row photos', () => {
  const jpeg = () =>
    new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: 'image/jpeg' });

  it('seeds a keep row at NZ$20+ with needs_photos', async () => {
    const client = createFakeClient();

    const detail = await client.getImport('fake-import-3');

    expect(detail.status).toBe('review');
    const flagged = detail.rows.find((row) => row.needs_photos);
    expect(flagged).toMatchObject({
      position: 1,
      name: 'Doubling Season',
      decision: 'keep',
      suggested_price: '60.00',
      photos: [],
      needs_photos: true,
    });
    expect(detail.rows.find((row) => row.position === 2)?.needs_photos).toBe(
      false,
    );
    expect(detail.rows.find((row) => row.position === 3)?.needs_photos).toBe(
      false,
    );
  });

  it('appends object-url photos in upload order and clears needs_photos', async () => {
    const client = createFakeClient();

    await client.addRowPhoto('fake-import-3', 1, jpeg());
    await client.addRowPhoto('fake-import-3', 1, jpeg());
    const detail = await client.getImport('fake-import-3');
    const row = detail.rows[0];

    expect(row.photos).toHaveLength(2);
    expect(row.photos[0].photo_id).toBe('fake-photo-1');
    expect(row.photos[1].photo_id).toBe('fake-photo-2');
    expect(row.photos[0].url).toMatch(/^blob:/);
    expect(row.photos[1].url).toMatch(/^blob:/);
    expect(row.photos[0].url).not.toBe(row.photos[1].url);
    expect(row.needs_photos).toBe(false);
  });

  it('promotes the next photo when the first is deleted', async () => {
    const client = createFakeClient();
    await client.addRowPhoto('fake-import-3', 1, jpeg());
    await client.addRowPhoto('fake-import-3', 1, jpeg());
    const before = (await client.getImport('fake-import-3')).rows[0];

    await client.deleteRowPhoto('fake-import-3', 1, before.photos[0].photo_id);
    const after = (await client.getImport('fake-import-3')).rows[0];

    expect(after.photos.map((photo) => photo.photo_id)).toEqual([
      before.photos[1].photo_id,
    ]);
    expect(after.needs_photos).toBe(false);
  });

  it('sets needs_photos again when the last photo is removed', async () => {
    const client = createFakeClient();
    await client.addRowPhoto('fake-import-3', 1, jpeg());
    const photoId = (await client.getImport('fake-import-3')).rows[0].photos[0]
      .photo_id;

    await client.deleteRowPhoto('fake-import-3', 1, photoId);

    expect((await client.getImport('fake-import-3')).rows[0].needs_photos).toBe(
      true,
    );
  });

  it('rejects a sixth photo', async () => {
    const client = createFakeClient();
    for (let i = 0; i < 5; i += 1) {
      await client.addRowPhoto('fake-import-3', 1, jpeg());
    }

    await expect(
      client.addRowPhoto('fake-import-3', 1, jpeg()),
    ).rejects.toThrow('a row may have at most 5 photos');
  });

  it('rejects photos on non-keep rows', async () => {
    const client = createFakeClient();

    await expect(
      client.addRowPhoto('fake-import-3', 3, jpeg()),
    ).rejects.toThrow('photos can only be created on keep rows');
  });

  it('rejects photo mutations unless the import is in review', async () => {
    const client = createFakeClient();

    await expect(
      client.addRowPhoto('fake-import-1', 1, jpeg()),
    ).rejects.toThrow('import is not in review status');
    await expect(
      client.deleteRowPhoto('fake-import-1', 1, 'fake-photo-1'),
    ).rejects.toThrow('import is not in review status');
  });

  it('rejects unknown photo ids', async () => {
    const client = createFakeClient();

    await expect(
      client.deleteRowPhoto('fake-import-3', 1, 'missing'),
    ).rejects.toThrow('Not Found');
  });
});

describe('createFakeClient orders', () => {
  it('seeds orders newest-first covering every state', async () => {
    const client = createFakeClient();

    const response = await client.findOrders();

    expect(response.orders.map((order) => order.state)).toEqual([
      'awaiting_payment',
      'to_pick',
      'fulfilled',
      'voided',
    ]);
    const acceptedAts = response.orders.map((order) => order.accepted_at);
    expect(acceptedAts).toEqual([...acceptedAts].sort((a, b) => b - a));
    const toPick = response.orders[1];
    expect(toPick.order_id).toBe('83647');
    expect(toPick.unit_count).toBe(3);
    expect(toPick.total_price).toBe('10.90');
    expect(toPick.items_total_price).toBe('10.90');
    expect(toPick.listed_total_price).toBe('13.00');
    expect(toPick.delivery_mode).toBe('PICKUP');
    expect(response.orders[0].items_total_price).toBe('479.90');
    expect(response.orders[0].listed_total_price).toBe('431.50');
    expect(response.orders[2].items_total_price).toBe('8.50');
    expect(response.orders[2].listed_total_price).toBe('8.50');
    expect(response.orders[3].items_total_price).toBe('4.20');
    expect(response.orders[3].listed_total_price).toBeNull();
  });

  it('returns order units ascending with derived locations', async () => {
    const client = createFakeClient();

    const detail = await client.getOrder('83647');

    expect(detail.state).toBe('to_pick');
    expect(detail.units).toHaveLength(3);
    const sequenceNumbers = detail.units.map((unit) => unit.sequence_number);
    expect(sequenceNumbers).toEqual([...sequenceNumbers].sort((a, b) => a - b));
    for (const unit of detail.units) {
      const block = Math.floor(unit.sequence_number / 100);
      expect(unit.location).toBe(`A${block}-${unit.sequence_number % 100}`);
    }
    expect(
      detail.units.filter((unit) => unit.name === 'Sol Ring'),
    ).toHaveLength(2);
    const aberration = detail.units.find(
      (unit) => unit.name === 'Elvish Aberration',
    );
    expect(aberration?.set_code).toBe('a25');
    expect(aberration?.collector_number).toBe('167');
    expect(aberration?.finish).toBe('normal');
    expect(aberration?.condition).toBe('NM');
    expect(detail.lines).toEqual([
      {
        name: 'Sol Ring',
        set_code: 'cmr',
        collector_number: '472',
        finish: 'normal',
        condition: 'NM',
        quantity: 2,
        price: '8.00',
        listed_price: '5.00',
      },
      {
        name: 'Elvish Aberration',
        set_code: 'a25',
        collector_number: '167',
        finish: 'normal',
        condition: 'NM',
        quantity: 1,
        price: '2.90',
        listed_price: '3.00',
      },
    ]);
  });

  it('rejects unknown order ids', async () => {
    const client = createFakeClient();

    await expect(client.getOrder('missing')).rejects.toThrow('Not Found');
  });

  it('marks units sold and fulfils the order on confirmOrder', async () => {
    const client = createFakeClient();

    const confirmed = await client.confirmOrder('83647');

    expect(confirmed.state).toBe('fulfilled');
    expect(confirmed.units).toHaveLength(3);

    const solRing = await client.getSku(
      '58b26011-e103-45c4-a253-900f4e6b2eeb#normal#NM',
    );
    expect(solRing.reserved_count).toBe(0);
    expect(solRing.sold_count).toBe(2);
    expect(solRing.in_stock_count).toBe(6);

    const aberration = await client.getSku(
      'f0a51425-d796-48b8-b68c-bc21fb465c81#normal#NM',
    );
    expect(aberration.reserved_count).toBe(0);
    expect(aberration.sold_count).toBe(1);

    const list = await client.findOrders();
    expect(list.orders.find((order) => order.order_id === '83647')?.state).toBe(
      'fulfilled',
    );
  });

  it('rejects confirmOrder unless the order is to_pick', async () => {
    const client = createFakeClient();

    await expect(client.confirmOrder('83663')).rejects.toThrow(
      'order is not ready to pick',
    );

    await client.confirmOrder('83647');
    await expect(client.confirmOrder('83647')).rejects.toThrow(
      'order is not ready to pick',
    );
  });
});

describe('createFakeClient publish', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  async function drainPublish(client: ReturnType<typeof createFakeClient>) {
    await client.createPublish();
    vi.advanceTimersByTime(60_000);
    return client.getPublish();
  }

  it('reports no run and seeded pending SKUs before the first trigger', async () => {
    const client = createFakeClient();

    const response = await client.getPublish();

    expect(response.status).toBeNull();
    expect(response.started_at).toBeNull();
    expect(response.finished_at).toBeNull();
    expect(response.pending_sku_count).toBeGreaterThan(0);
  });

  it('drains dirty SKUs over time until the run succeeds', async () => {
    vi.useFakeTimers();
    const client = createFakeClient();
    const before = await client.getPublish();

    await client.createPublish();
    const started = await client.getPublish();
    expect(started.status).toBe('running');
    expect(started.total_sku_count).toBe(before.pending_sku_count);
    expect(started.published_sku_count).toBe(0);

    vi.advanceTimersByTime(1000);
    const mid = await client.getPublish();
    expect(mid.status).toBe('running');
    expect(mid.published_sku_count).toBe(2);
    expect(mid.pending_sku_count).toBe(before.pending_sku_count - 2);

    vi.advanceTimersByTime(60_000);
    const done = await client.getPublish();
    expect(done.status).toBe('succeeded');
    expect(done.published_sku_count).toBe(done.total_sku_count);
    expect(done.pending_sku_count).toBe(0);
    expect(done.finished_at).not.toBeNull();
  });

  it('keeps the existing run when triggered while one is running', async () => {
    vi.useFakeTimers();
    const client = createFakeClient();

    await client.createPublish();
    const first = await client.getPublish();
    vi.advanceTimersByTime(1000);
    await client.createPublish();
    const second = await client.getPublish();

    expect(second.status).toBe('running');
    expect(second.started_at).toBe(first.started_at);
    expect(second.total_sku_count).toBe(first.total_sku_count);
    expect(second.published_sku_count).toBe(2);
  });

  it('starts a fresh run after the previous run completed', async () => {
    vi.useFakeTimers();
    const client = createFakeClient();
    await drainPublish(client);

    const skuId = await findSkuId(client, 'brainstorm');
    const detail = await client.getSku(skuId);
    const unit = detail.units.find((entry) => entry.status === 'in_stock');
    await client.deleteUnit(skuId, unit!.sequence_number);

    await client.createPublish();
    const rerun = await client.getPublish();
    expect(rerun.status).toBe('running');
    expect(rerun.total_sku_count).toBe(1);
    expect(rerun.published_sku_count).toBe(0);
  });

  it('completes immediately when nothing is pending', async () => {
    vi.useFakeTimers();
    const client = createFakeClient();
    await drainPublish(client);

    await client.createPublish();
    const response = await client.getPublish();

    expect(response.status).toBe('succeeded');
    expect(response.total_sku_count).toBe(0);
    expect(response.pending_sku_count).toBe(0);
    expect(response.finished_at).toBe(response.started_at);
  });

  it('marks SKUs dirty on adjustments but not on confirmed pulls', async () => {
    vi.useFakeTimers();
    const client = createFakeClient();
    await drainPublish(client);
    expect((await client.getPublish()).pending_sku_count).toBe(0);

    await client.confirmOrder('83647');
    expect((await client.getPublish()).pending_sku_count).toBe(0);

    const skuId = await findSkuId(client, 'brainstorm');
    const detail = await client.getSku(skuId);
    const units = detail.units.filter((entry) => entry.status === 'in_stock');
    await client.deleteUnit(skuId, units[0].sequence_number);
    expect((await client.getPublish()).pending_sku_count).toBe(1);

    await client.updateUnit(skuId, units[1].sequence_number, 'LP');
    expect((await client.getPublish()).pending_sku_count).toBe(2);
  });

  it('marks keeper SKUs dirty when an import is confirmed', async () => {
    vi.useFakeTimers();
    const client = createFakeClient();
    await drainPublish(client);

    await client.confirmImport('fake-import-2');

    const response = await client.getPublish();
    expect(response.pending_sku_count).toBeGreaterThan(0);
  });
});

describe('createFakeClient settings', () => {
  it('stores credential presence without exposing the value', async () => {
    const client = createFakeClient();

    expect(await client.getSettings()).toEqual({
      credential_set: false,
      updated_at: null,
      track_orders_after: null,
    });

    const response = await client.updateSettings({
      refresh_token: 'super-secret-refresh-token',
    });

    expect(response.credential_set).toBe(true);
    expect(response.updated_at).not.toBeNull();
    expect(JSON.stringify(response)).not.toContain(
      'super-secret-refresh-token',
    );

    const fetched = await client.getSettings();
    expect(fetched.credential_set).toBe(true);
    expect(fetched.updated_at).toBe(response.updated_at);
  });

  it('stores track_orders_after without affecting credential', async () => {
    const client = createFakeClient();

    const response = await client.updateSettings({
      track_orders_after: 1723363200,
    });

    expect(response.credential_set).toBe(false);
    expect(response.track_orders_after).toBe(1723363200);

    const fetched = await client.getSettings();
    expect(fetched.track_orders_after).toBe(1723363200);
  });

  it('preserves track_orders_after when updating credential', async () => {
    const client = createFakeClient();

    await client.updateSettings({ track_orders_after: 1723363200 });
    const response = await client.updateSettings({
      refresh_token: 'token',
    });

    expect(response.credential_set).toBe(true);
    expect(response.track_orders_after).toBe(1723363200);
  });
});
