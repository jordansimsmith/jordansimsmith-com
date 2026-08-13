import { describe, it, expect } from 'vitest';
import { parseManaBoxCsv } from './manabox';

// real ManaBox exports include extra columns; the parser must ignore them
const HEADER =
  'Name,Set code,Set name,Collector number,Foil,Rarity,Quantity,ManaBox ID,Scryfall ID,Purchase price,Misprint,Altered,Condition,Language,Purchase price currency';

interface CsvRowValues {
  name?: string;
  setCode?: string;
  foil?: string;
  quantity?: string;
  scryfallId?: string;
  misprint?: string;
  altered?: string;
  condition?: string;
  language?: string;
}

function csvRow(values: CsvRowValues = {}): string {
  return [
    values.name ?? 'Lightning Bolt',
    values.setCode ?? 'STA',
    'Strixhaven Mystical Archive',
    '42',
    values.foil ?? 'normal',
    'rare',
    values.quantity ?? '1',
    '12345',
    values.scryfallId ?? '4eaac4fd-95f5-4f38-b593-0101e79a20f9',
    '0.25',
    values.misprint ?? 'false',
    values.altered ?? 'false',
    values.condition ?? 'near_mint',
    values.language ?? 'en',
    'NZD',
  ].join(',');
}

function csv(...rows: string[]): string {
  return [HEADER, ...rows].join('\n');
}

describe('parseManaBoxCsv', () => {
  it('parses rows into normalized fields', () => {
    const rows = parseManaBoxCsv(csv(csvRow({ quantity: '3' })));

    expect(rows).toHaveLength(1);
    expect(rows[0]).toEqual({
      name: 'Lightning Bolt',
      set_code: 'sta',
      set_name: 'Strixhaven Mystical Archive',
      collector_number: '42',
      finish: 'normal',
      condition: 'NM',
      scryfall_id: '4eaac4fd-95f5-4f38-b593-0101e79a20f9',
      quantity: 3,
      language: 'en',
    });
  });

  it('parses quoted fields containing commas', () => {
    const rows = parseManaBoxCsv(
      csv(csvRow({ name: '"Borborygmos, Enraged"' })),
    );

    expect(rows[0].name).toBe('Borborygmos, Enraged');
  });

  it('strips a leading byte order mark', () => {
    const rows = parseManaBoxCsv(`\ufeff${csv(csvRow())}`);

    expect(rows).toHaveLength(1);
  });

  it.each([
    ['mint', 'NM'],
    ['near_mint', 'NM'],
    ['excellent', 'LP'],
    ['good', 'MP'],
    ['light_played', 'HP'],
    ['played', 'HP'],
    ['poor', 'DMG'],
  ])('maps ManaBox condition %s to %s', (manaboxCondition, condition) => {
    const rows = parseManaBoxCsv(csv(csvRow({ condition: manaboxCondition })));

    expect(rows[0].condition).toBe(condition);
  });

  it('rejects unknown conditions', () => {
    expect(() => parseManaBoxCsv(csv(csvRow({ condition: 'sealed' })))).toThrow(
      'row 2: unknown Condition sealed',
    );
  });

  it('rejects a csv with missing columns', () => {
    const content = ['Name,Quantity', 'Lightning Bolt,1'].join('\n');

    expect(() => parseManaBoxCsv(content)).toThrow(
      'CSV is missing columns: Set code, Set name, Collector number, Foil, Scryfall ID, Condition, Language',
    );
  });

  it('rejects empty content', () => {
    expect(() => parseManaBoxCsv('')).toThrow('CSV is empty');
    expect(() => parseManaBoxCsv('  \n \n')).toThrow('CSV is empty');
  });

  it('rejects a header without data rows', () => {
    expect(() => parseManaBoxCsv(`${HEADER}\n`)).toThrow(
      'CSV contains no cards',
    );
  });

  it('rejects non-positive and non-integer quantities', () => {
    expect(() => parseManaBoxCsv(csv(csvRow({ quantity: '0' })))).toThrow(
      'row 2: Quantity must be a positive integer',
    );
    expect(() => parseManaBoxCsv(csv(csvRow({ quantity: 'two' })))).toThrow(
      'row 2: Quantity must be a positive integer',
    );
  });

  it('rejects unknown finishes', () => {
    expect(() => parseManaBoxCsv(csv(csvRow({ foil: 'gilded' })))).toThrow(
      'row 2: Foil must be normal, foil, or etched',
    );
  });

  it('accepts etched finishes', () => {
    const rows = parseManaBoxCsv(csv(csvRow({ foil: 'Etched' })));

    expect(rows[0].finish).toBe('etched');
  });

  it('rejects malformed Scryfall IDs', () => {
    expect(() =>
      parseManaBoxCsv(csv(csvRow({ scryfallId: 'not-a-uuid' }))),
    ).toThrow('row 2: Scryfall ID must be a UUID');
  });

  it('rejects empty required fields', () => {
    expect(() => parseManaBoxCsv(csv(csvRow({ name: '' })))).toThrow(
      'row 2: Name must not be empty',
    );
  });

  it('ignores misprint and altered columns', () => {
    const rows = parseManaBoxCsv(
      csv(csvRow({ misprint: 'yes', altered: 'true' })),
    );

    expect(rows).toHaveLength(1);
    expect(rows[0].name).toBe('Lightning Bolt');
  });

  it('reports the csv line number of the failing row', () => {
    expect(() =>
      parseManaBoxCsv(csv(csvRow(), csvRow({ quantity: '0' }))),
    ).toThrow('row 3: Quantity must be a positive integer');
  });
});
