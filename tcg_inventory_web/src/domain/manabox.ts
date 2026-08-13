import type { Condition, Finish } from '../api/client';

export const REQUIRED_COLUMNS = [
  'Name',
  'Set code',
  'Set name',
  'Collector number',
  'Foil',
  'Quantity',
  'Scryfall ID',
  'Condition',
  'Language',
];

const FINISHES: Finish[] = ['normal', 'foil', 'etched'];

const MANABOX_CONDITIONS: Record<string, Condition> = {
  mint: 'NM',
  near_mint: 'NM',
  excellent: 'LP',
  good: 'MP',
  light_played: 'HP',
  played: 'HP',
  poor: 'DMG',
};

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export interface ManaBoxRow {
  name: string;
  set_code: string;
  set_name: string;
  collector_number: string;
  finish: Finish;
  condition: Condition;
  scryfall_id: string;
  quantity: number;
  language: string;
}

function parseCsvTable(content: string): string[][] {
  const text = content.startsWith('\ufeff') ? content.slice(1) : content;
  const rows: string[][] = [];
  let row: string[] = [];
  let field = '';
  let inQuotes = false;
  let index = 0;

  while (index < text.length) {
    const character = text[index];
    if (inQuotes) {
      if (character === '"') {
        if (text[index + 1] === '"') {
          field += '"';
          index += 2;
          continue;
        }
        inQuotes = false;
        index += 1;
        continue;
      }
      field += character;
      index += 1;
      continue;
    }
    if (character === '"') {
      inQuotes = true;
      index += 1;
      continue;
    }
    if (character === ',') {
      row.push(field);
      field = '';
      index += 1;
      continue;
    }
    if (character === '\n' || character === '\r') {
      if (character === '\r' && text[index + 1] === '\n') {
        index += 1;
      }
      row.push(field);
      rows.push(row);
      row = [];
      field = '';
      index += 1;
      continue;
    }
    field += character;
    index += 1;
  }
  if (field !== '' || row.length > 0) {
    row.push(field);
    rows.push(row);
  }

  return rows.filter((cells) => cells.some((cell) => cell.trim() !== ''));
}

function parseRow(
  header: string[],
  cells: string[],
  rowNumber: number,
): ManaBoxRow {
  const get = (column: string): string => {
    const columnIndex = header.indexOf(column);
    return (cells[columnIndex] ?? '').trim();
  };
  const required = (column: string): string => {
    const value = get(column);
    if (!value) {
      throw new Error(`row ${rowNumber}: ${column} must not be empty`);
    }
    return value;
  };

  const quantity = Number(required('Quantity'));
  if (!Number.isInteger(quantity) || quantity <= 0) {
    throw new Error(`row ${rowNumber}: Quantity must be a positive integer`);
  }

  const finish = required('Foil').toLowerCase() as Finish;
  if (!FINISHES.includes(finish)) {
    throw new Error(`row ${rowNumber}: Foil must be normal, foil, or etched`);
  }

  const scryfallId = required('Scryfall ID').toLowerCase();
  if (!UUID_PATTERN.test(scryfallId)) {
    throw new Error(`row ${rowNumber}: Scryfall ID must be a UUID`);
  }

  const conditionValue = required('Condition').toLowerCase();
  const condition = MANABOX_CONDITIONS[conditionValue];
  if (!condition) {
    throw new Error(`row ${rowNumber}: unknown Condition ${conditionValue}`);
  }

  return {
    name: required('Name'),
    set_code: required('Set code').toLowerCase(),
    set_name: required('Set name'),
    collector_number: required('Collector number'),
    finish,
    condition,
    scryfall_id: scryfallId,
    quantity,
    language: required('Language').toLowerCase(),
  };
}

export function parseManaBoxCsv(content: string): ManaBoxRow[] {
  const table = parseCsvTable(content);
  if (table.length === 0) {
    throw new Error('CSV is empty');
  }

  const header = table[0].map((cell) => cell.trim());
  const missing = REQUIRED_COLUMNS.filter((column) => !header.includes(column));
  if (missing.length > 0) {
    throw new Error(`CSV is missing columns: ${missing.join(', ')}`);
  }

  const dataRows = table.slice(1);
  if (dataRows.length === 0) {
    throw new Error('CSV contains no cards');
  }

  // csv line numbers start at 2 because line 1 is the header
  return dataRows.map((cells, index) => parseRow(header, cells, index + 2));
}
