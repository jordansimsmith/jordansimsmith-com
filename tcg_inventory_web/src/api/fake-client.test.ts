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
  it('derives browse counts from units', async () => {
    const client = createFakeClient();

    const response = await client.findSkus({ search: 'sol ring' });

    expect(response.skus).toHaveLength(2);
    const nm = response.skus.find((sku) => sku.condition === 'NM');
    expect(nm?.in_stock_count).toBe(6);
    expect(nm?.reserved_count).toBe(2);
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

    const updated = await client.deleteUnit(
      skuId,
      unit!.sequence_number,
      'damaged in storage',
    );

    expect(updated.in_stock_count).toBe(detail.in_stock_count - 1);
    expect(
      updated.units.find(
        (entry) => entry.sequence_number === unit!.sequence_number,
      )?.status,
    ).toBe('removed');

    const browse = await client.findSkus({ search: 'brainstorm' });
    expect(browse.skus[0].in_stock_count).toBe(detail.in_stock_count - 1);
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

    expect(response.imports).toHaveLength(2);
    const [inFlight, confirmed] = response.imports;
    expect(inFlight.status).toBe('appraising');
    expect(inFlight.created_at).toBeGreaterThan(confirmed.created_at);
    const inFlightAppraised =
      inFlight.keep_count + inFlight.discard_count + inFlight.review_count;
    expect(inFlightAppraised).toBeGreaterThan(0);
    expect(inFlightAppraised).toBeLessThan(inFlight.row_count);
    expect(confirmed.status).toBe('confirmed');
    expect(
      confirmed.keep_count + confirmed.discard_count + confirmed.review_count,
    ).toBe(confirmed.row_count);
  });

  it('creates an import with quantity-expanded rows top-of-stack first', async () => {
    vi.useFakeTimers();
    const client = createFakeClient();

    const created = await client.createImport('bulk.csv', SAMPLE_CSV);

    expect(created.status).toBe('appraising');
    expect(created.filename).toBe('bulk.csv');
    expect(created.row_count).toBe(4);
    expect(created.keep_count).toBe(0);
    expect(created.discard_count).toBe(0);
    expect(created.review_count).toBe(0);

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
    expect(detail.keep_count + detail.discard_count + detail.review_count).toBe(
      2,
    );

    vi.advanceTimersByTime(60_000);
    detail = await client.getImport(created.import_id);
    expect(detail.status).toBe('review');
    expect(detail.keep_count).toBe(3);
    expect(detail.discard_count).toBe(0);
    expect(detail.review_count).toBe(1);
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
    expect(
      response.imports[0].keep_count +
        response.imports[0].discard_count +
        response.imports[0].review_count,
    ).toBe(response.imports[0].row_count);
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
        unit_count: 100,
      },
      {
        block: 'A7',
        from_location: 'A7-0',
        to_location: 'A7-3',
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
