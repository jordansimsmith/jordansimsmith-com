import { describe, it, expect } from 'vitest';
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
