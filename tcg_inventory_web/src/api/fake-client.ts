import type {
  ApiClient,
  Condition,
  ConfirmImportResponse,
  Finish,
  FindImportsResponse,
  FindOrdersResponse,
  FindSkusParams,
  FindSkusResponse,
  GenerationStatus,
  ImportDetail,
  ImportRow,
  ImportStatus,
  ImportSummary,
  OrderDetail,
  OrderLine,
  OrderState,
  OrderSummary,
  OrderUnit,
  PlacementInstruction,
  PublishResponse,
  Report,
  ReportResponse,
  RowDecision,
  RowPhoto,
  SettingsResponse,
  SkuDetail,
  SkuSummary,
  SkuUnit,
  UnitStatus,
  UpdateSettingsRequest,
  UpdateUnitResponse,
} from './client';
import { parseManaBoxCsv } from '../domain/manabox';
import type { ManaBoxRow } from '../domain/manabox';

type SeedSku = [
  scryfallId: string,
  name: string,
  setCode: string,
  setName: string,
  collectorNumber: string,
  finish: Finish,
  condition: Condition,
  inStockCount: number,
  reservedCount: number,
  soldCount?: number,
];

// prettier-ignore
const seedSkus: SeedSku[] = [
  ['bd3d4b4b-cf31-4f89-8140-9650edb03c7b', 'Ancient Tomb', 'uma', 'Ultimate Masters', '236', 'normal', 'LP', 1, 0],
  ['01b186af-8825-4257-80fd-9c1ecdb21414', 'Arcane Signet', 'c21', 'Commander 2021', '234', 'normal', 'NM', 5, 1],
  ['307d4236-1e54-43e3-83f1-063d49d16dda', 'Birds of Paradise', 'm12', 'Magic 2012', '165', 'normal', 'MP', 2, 0],
  ['18adbda4-8d36-47cd-afbc-c785aaa8ed80', 'Blightsteel Colossus', '2xm', 'Double Masters', '235', 'normal', 'NM', 1, 0],
  ['3e4e6787-af32-44f2-ac56-6f348254aa6d', 'Brainstorm', 'ema', 'Eternal Masters', '40', 'normal', 'NM', 4, 0],
  ['1920dae4-fb92-4f19-ae4b-eb3276b8dac7', 'Counterspell', 'mh2', 'Modern Horizons 2', '267', 'normal', 'NM', 3, 0],
  ['1920dae4-fb92-4f19-ae4b-eb3276b8dac7', 'Counterspell', 'mh2', 'Modern Horizons 2', '267', 'foil', 'NM', 1, 0],
  ['4a433310-3fe2-4156-864d-7a6b2638340b', 'Cultivate', 'm21', 'Core Set 2021', '177', 'normal', 'NM', 6, 0],
  ['205c4689-8b02-4d40-9274-3c1fcafa8b82', 'Cyclonic Rift', 'rtr', 'Return to Ravnica', '35', 'normal', 'LP', 2, 1],
  ['95f27eeb-6f14-4db3-adb9-9be5ed76b34b', 'Dark Ritual', 'a25', 'Masters 25', '82', 'normal', 'NM', 3, 0],
  ['a24b4cb6-cebb-428b-8654-74347a6a8d63', 'Demonic Tutor', 'cmm', 'Commander Masters', '150', 'normal', 'MP', 1, 0],
  ['571bc9eb-8d13-4008-86b5-2e348a326d58', 'Dockside Extortionist', 'c19', 'Commander 2019', '24', 'normal', 'LP', 1, 1],
  ['29ba5a2d-d787-4214-8cd7-7f2bcea938f8', 'Doubling Season', 'bbd', 'Battlebond', '195', 'normal', 'NM', 1, 0],
  ['f0a51425-d796-48b8-b68c-bc21fb465c81', 'Elvish Aberration', 'a25', 'Masters 25', '167', 'normal', 'NM', 2, 1],
  ['deb5ca6a-f91d-443d-ad84-6fe1e80bfb51', 'Elvish Mystic', 'm15', 'Magic 2015', '173', 'normal', 'NM', 6, 0],
  ['39704000-65d3-4d39-849e-a3b617376bbc', 'Eternal Witness', 'cmm', 'Commander Masters', '286', 'normal', 'NM', 3, 0],
  ['b841bfa8-7c17-4df2-8466-780ab9a4a53a', 'Fabled Passage', 'eld', 'Throne of Eldraine', '244', 'normal', 'NM', 2, 0],
  ['c1aac0f5-1d01-4673-b8d3-878d9a1d423c', 'Farseek', 'msc', 'Marvel Super Heroes Commander', '173', 'normal', 'LP', 4, 0],
  ['3ef87948-0ad9-4757-a692-2262c8e24367', 'Fellwar Stone', 'mbc', 'Mystery Booster Commander Edition', '74', 'normal', 'MP', 3, 0],
  ['e9be371c-c688-44ad-ab71-bd4c9f242d58', 'Force of Negation', 'mh1', 'Modern Horizons', '52', 'foil', 'NM', 1, 0],
  ['82d7de2b-c909-48dc-9ab7-c4a8328e37bb', 'Ghostly Prison', 'chk', 'Champions of Kamigawa', '10', 'normal', 'HP', 0, 0],
  ['b2e2a777-0705-4a37-937d-c6e020ebc0f0', 'Goblin Guide', 'zen', 'Zendikar', '126', 'normal', 'DMG', 1, 0],
  ['0bc3401f-935b-45ce-b1e6-300a5d9dfd4f', 'Hellkite Tyrant', 'gtc', 'Gatecrash', '94', 'normal', 'NM', 1, 0, 1],
  ['48caf4c4-745c-4072-bf3d-1a3fa7c3bc9c', 'Jeska, Thrice Reborn', 'cmr', 'Commander Legends', '186', 'etched', 'NM', 1, 0],
  ['85d207ac-0680-47ef-85d9-4323c1321d6f', "Kodama's Reach", 'chk', 'Champions of Kamigawa', '225', 'normal', 'MP', 5, 0],
  ['4eaac4fd-95f5-4f38-b593-0101e79a20f9', 'Lightning Bolt', 'sta', 'Strixhaven Mystical Archive', '42', 'normal', 'NM', 4, 0],
  ['4eaac4fd-95f5-4f38-b593-0101e79a20f9', 'Lightning Bolt', 'sta', 'Strixhaven Mystical Archive', '42', 'normal', 'LP', 2, 0],
  ['b61634ae-05be-4b56-8ebb-9d4ade902e42', 'Lightning Greaves', 'msc', 'Marvel Super Heroes Commander', '202', 'normal', 'NM', 3, 1],
  ['581b7327-3215-4a4f-b4ae-d9d4002ba882', 'Llanowar Elves', 'dom', 'Dominaria', '168', 'normal', 'NM', 6, 0],
  ['4d960186-4559-4af0-bd22-63baa15f8939', 'Mana Crypt', '2xm', 'Double Masters', '270', 'normal', 'NM', 0, 1],
  ['40140991-cffa-4b52-9a25-37e9a8aa9ddd', 'Mystic Remora', 'dmr', 'Dominaria Remastered', '59', 'normal', 'LP', 2, 0],
  ['25f2e4d0-effd-4e83-b7aa-1a0d8f120951', 'Opt', 'dom', 'Dominaria', '60', 'normal', 'NM', 5, 0],
  ['061df0a2-1967-4ddd-84e3-3ecf3af98f6b', 'Path to Exile', 'mm3', 'Modern Masters 2017', '17', 'normal', 'NM', 3, 0],
  ['81c908ee-e70a-4406-a32d-ab5ab17e67b1', 'Ponder', 'm12', 'Magic 2012', '73', 'normal', 'HP', 1, 0],
  ['d6914dba-0d27-4055-ac34-b3ebf5802221', 'Rhystic Study', 'jmp', 'Jumpstart', '169', 'normal', 'NM', 2, 1],
  ['91c7707a-bae0-4196-bf26-d276f57b7369', 'Sakura-Tribe Elder', 'chk', 'Champions of Kamigawa', '239', 'normal', 'NM', 4, 0],
  ['58b26011-e103-45c4-a253-900f4e6b2eeb', 'Sol Ring', 'cmr', 'Commander Legends', '472', 'normal', 'NM', 6, 2],
  ['58b26011-e103-45c4-a253-900f4e6b2eeb', 'Sol Ring', 'cmr', 'Commander Legends', '472', 'normal', 'MP', 2, 0],
  ['b5c45f3d-cf12-4db7-b161-9539ed969ca7', 'Swiftfoot Boots', 'cmr', 'Commander Legends', '474', 'normal', 'NM', 3, 0],
  ['cc9ece2f-7eda-4fc5-a562-3e16e71560e9', 'Swords to Plowshares', 'sta', 'Strixhaven Mystical Archive', '10', 'normal', 'NM', 2, 0],
  ['d5b5d2a7-8185-4df0-a35a-f89c12857f87', 'Sylvan Library', 'ema', 'Eternal Masters', '187', 'normal', 'LP', 1, 0],
];

interface FakeUnit {
  sequence_number: number;
  status: UnitStatus;
  photos: RowPhoto[];
}

const SEEDED_UNIT_PHOTO_URL =
  "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='40' height='40'%3E%3Crect width='100%25' height='100%25' fill='%23868e96'/%3E%3C/svg%3E";

interface FakeSku {
  sku_id: string;
  scryfall_id: string;
  name: string;
  set_code: string;
  set_name: string;
  collector_number: string;
  finish: Finish;
  condition: Condition;
  last_published_price: string | null;
  units: FakeUnit[];
}

function createSeedState(): FakeSku[] {
  let unitIndex = 0;
  const skus = seedSkus.map(
    ([
      scryfallId,
      name,
      setCode,
      setName,
      collectorNumber,
      finish,
      condition,
      inStockCount,
      reservedCount,
      soldCount = 0,
    ]) => {
      const units: FakeUnit[] = [];
      for (let i = 0; i < inStockCount + reservedCount + soldCount; i += 1) {
        // stride 37 is coprime with 600, scattering units across blocks A0-A5
        units.push({
          sequence_number: (unitIndex * 37) % 600,
          status: 'in_stock',
          photos: [],
        });
        unitIndex += 1;
      }
      units.sort((a, b) => a.sequence_number - b.sequence_number);
      // pulls and reservations take the forward-most units, in that order
      for (let i = 0; i < soldCount; i += 1) {
        units[i].status = 'sold';
      }
      for (let i = soldCount; i < soldCount + reservedCount; i += 1) {
        units[i].status = 'reserved';
      }
      const hasStock = inStockCount > 0;
      return {
        sku_id: `${scryfallId}#${finish}#${condition}`,
        scryfall_id: scryfallId,
        name,
        set_code: setCode,
        set_name: setName,
        collector_number: collectorNumber,
        finish,
        condition,
        last_published_price: hasStock
          ? formatPrice(30 + ((unitIndex * 37) % 20) * 25)
          : null,
        units,
      };
    },
  );
  const doublingSeason = skus.find((sku) => sku.name === 'Doubling Season');
  const photographed = doublingSeason?.units.find(
    (unit) => unit.status === 'in_stock',
  );
  if (!photographed) {
    throw new Error('expected a seeded in-stock Doubling Season unit');
  }
  photographed.photos = [
    { photo_id: 'fake-unit-photo-1', url: SEEDED_UNIT_PHOTO_URL },
  ];
  return skus;
}

function deriveBlock(sequenceNumber: number): string {
  const block = Math.floor(sequenceNumber / 100);
  const letter = String.fromCharCode(
    'A'.charCodeAt(0) + Math.floor(block / 100),
  );
  return `${letter}${block % 100}`;
}

function deriveLocation(sequenceNumber: number): string {
  return `${deriveBlock(sequenceNumber)}-${sequenceNumber % 100}`;
}

function countByStatus(sku: FakeSku, status: UnitStatus): number {
  return sku.units.filter((unit) => unit.status === status).length;
}

function toSummary(sku: FakeSku): SkuSummary {
  return {
    sku_id: sku.sku_id,
    name: sku.name,
    set_code: sku.set_code,
    set_name: sku.set_name,
    collector_number: sku.collector_number,
    finish: sku.finish,
    condition: sku.condition,
    last_published_price: sku.last_published_price,
  };
}

function toDetail(sku: FakeSku): SkuDetail {
  const units: SkuUnit[] = [...sku.units]
    .sort((a, b) => a.sequence_number - b.sequence_number)
    .map((unit) => ({
      sequence_number: unit.sequence_number,
      location: deriveLocation(unit.sequence_number),
      status: unit.status,
      photos: unit.photos.map((photo) => ({ ...photo })),
    }));
  return {
    ...toSummary(sku),
    scryfall_id: sku.scryfall_id,
    in_stock_count: countByStatus(sku, 'in_stock'),
    reserved_count: countByStatus(sku, 'reserved'),
    sold_count: countByStatus(sku, 'sold'),
    units,
  };
}

function browseKey(sku: SkuSummary): string {
  return `${sku.name.toLowerCase()}#${sku.sku_id}`;
}

const APPRAISAL_ROWS_PER_SECOND = 2;

const DISCARD_REASON = 'market price below NZ$0.25 keep threshold';

const SEED_REVIEW_REASONS = [
  'non-English card',
  'unmapped set',
  'unresolvable identity',
];

interface FakeImportRow {
  position: number;
  name: string;
  set_code: string;
  set_name: string;
  collector_number: string;
  finish: Finish;
  condition: Condition;
  scryfall_id: string;
  decision: RowDecision;
  decision_reason: string | null;
  market_price: string | null;
  suggested_price: string | null;
  photos: RowPhoto[];
}

function formatPrice(cents: number): string {
  return (cents / 100).toFixed(2);
}

function appraisePrices(
  decision: RowDecision,
  position: number,
): Pick<FakeImportRow, 'market_price' | 'suggested_price'> {
  if (decision === 'review') {
    return { market_price: null, suggested_price: null };
  }
  if (decision === 'discard') {
    // below the NZ$0.25 keep threshold
    return {
      market_price: formatPrice(5 + ((position * 3) % 4) * 5),
      suggested_price: null,
    };
  }
  const marketCents = 30 + ((position * 37) % 20) * 25;
  return {
    market_price: formatPrice(marketCents),
    suggested_price: formatPrice(Math.max(25, marketCents - 5)),
  };
}

interface FakeImport {
  import_id: string;
  filename: string;
  status: ImportStatus;
  rows: FakeImportRow[];
  created_at_ms: number;
}

function decideRow(
  row: ManaBoxRow,
  position: number,
): Pick<FakeImportRow, 'decision' | 'decision_reason'> {
  if (row.language !== 'en') {
    return { decision: 'review', decision_reason: 'non-English card' };
  }
  if (position % 5 === 0) {
    return { decision: 'discard', decision_reason: DISCARD_REASON };
  }
  return { decision: 'keep', decision_reason: null };
}

function createSeedImportRows(count: number): FakeImportRow[] {
  const rows: FakeImportRow[] = [];
  for (let position = 1; position <= count; position += 1) {
    const [
      scryfallId,
      name,
      setCode,
      setName,
      collectorNumber,
      finish,
      condition,
    ] = seedSkus[(position - 1) % seedSkus.length];
    let decision: RowDecision = 'keep';
    let decisionReason: string | null = null;
    if (position % 7 === 0) {
      decision = 'discard';
      decisionReason = DISCARD_REASON;
    } else if (position % 11 === 0) {
      decision = 'review';
      decisionReason =
        SEED_REVIEW_REASONS[(position / 11 - 1) % SEED_REVIEW_REASONS.length];
    }
    rows.push({
      position,
      name,
      set_code: setCode,
      set_name: setName,
      collector_number: collectorNumber,
      finish,
      condition,
      scryfall_id: scryfallId,
      decision,
      decision_reason: decisionReason,
      ...appraisePrices(decision, position),
      photos: [],
    });
  }
  return rows;
}

function createSeedImports(): FakeImport[] {
  const now = Date.now();
  return [
    {
      import_id: 'fake-import-1',
      filename: 'manabox-2026-08-05.csv',
      status: 'confirmed',
      // review rows must be resolved before confirm, so none remain here
      rows: createSeedImportRows(24).map((row) =>
        row.decision === 'review'
          ? { ...row, decision: 'keep' as RowDecision, decision_reason: null }
          : row,
      ),
      created_at_ms: now - 7 * 24 * 60 * 60 * 1000,
    },
    {
      // partway through appraisal at app load; finishes ~10s later
      import_id: 'fake-import-2',
      filename: 'manabox-2026-08-12.csv',
      status: 'appraising',
      rows: createSeedImportRows(40),
      created_at_ms: now - 10_000,
    },
    {
      import_id: 'fake-import-3',
      filename: 'manabox-2026-08-19.csv',
      status: 'review',
      rows: [
        {
          position: 1,
          name: 'Doubling Season',
          set_code: 'bbd',
          set_name: 'Battlebond',
          collector_number: '195',
          finish: 'normal',
          condition: 'NM',
          scryfall_id: '29ba5a2d-d787-4214-8cd7-7f2bcea938f8',
          decision: 'keep',
          decision_reason: null,
          market_price: '62.00',
          suggested_price: '60.00',
          photos: [],
        },
        {
          position: 2,
          name: 'Llanowar Elves',
          set_code: 'dom',
          set_name: 'Dominaria',
          collector_number: '168',
          finish: 'normal',
          condition: 'NM',
          scryfall_id: '581b7327-3215-4a4f-b4ae-d9d4002ba882',
          decision: 'keep',
          decision_reason: null,
          market_price: '0.30',
          suggested_price: '0.25',
          photos: [],
        },
        {
          position: 3,
          name: 'Opt',
          set_code: 'dom',
          set_name: 'Dominaria',
          collector_number: '60',
          finish: 'normal',
          condition: 'NM',
          scryfall_id: '25f2e4d0-effd-4e83-b7aa-1a0d8f120951',
          decision: 'discard',
          decision_reason: DISCARD_REASON,
          market_price: '0.10',
          suggested_price: null,
          photos: [],
        },
      ],
      created_at_ms: now - 24 * 60 * 60 * 1000,
    },
  ];
}

function appraisedCount(importRecord: FakeImport): number {
  if (importRecord.status !== 'appraising') {
    return importRecord.rows.length;
  }
  const elapsedMs = Math.max(0, Date.now() - importRecord.created_at_ms);
  return Math.min(
    importRecord.rows.length,
    Math.floor((elapsedMs / 1000) * APPRAISAL_ROWS_PER_SECOND),
  );
}

function progressAppraisal(importRecord: FakeImport): void {
  if (
    importRecord.status === 'appraising' &&
    appraisedCount(importRecord) >= importRecord.rows.length
  ) {
    importRecord.status = 'review';
  }
}

function toImportSummary(importRecord: FakeImport): ImportSummary {
  return {
    import_id: importRecord.import_id,
    filename: importRecord.filename,
    status: importRecord.status,
    row_count: importRecord.rows.length,
    appraisal_error: null,
    created_at: Math.floor(importRecord.created_at_ms / 1000),
  };
}

function needsPhotos(row: FakeImportRow): boolean {
  return (
    row.decision === 'keep' &&
    row.suggested_price != null &&
    Number(row.suggested_price) >= 20 &&
    row.photos.length === 0
  );
}

function toImportRow(row: FakeImportRow, revealed: boolean): ImportRow {
  return {
    position: row.position,
    name: row.name,
    set_code: row.set_code,
    set_name: row.set_name,
    collector_number: row.collector_number,
    finish: row.finish,
    condition: row.condition,
    scryfall_id: row.scryfall_id,
    decision: revealed ? row.decision : null,
    decision_reason: revealed ? row.decision_reason : null,
    market_price: revealed ? row.market_price : null,
    suggested_price: revealed ? row.suggested_price : null,
    photos: row.photos.map((photo) => ({ ...photo })),
    needs_photos: revealed && needsPhotos(row),
  };
}

function toImportDetail(importRecord: FakeImport): ImportDetail {
  const appraised = appraisedCount(importRecord);
  return {
    ...toImportSummary(importRecord),
    rows: importRecord.rows.map((row, index) =>
      toImportRow(row, index < appraised),
    ),
  };
}

interface FakeOrderUnitRef {
  sku_id: string;
  sequence_number: number;
}

interface FakeOrderLine {
  sku_id: string;
  quantity: number;
  price: string;
  listed_price: string | null;
}

interface FakeOrder {
  order_id: string;
  state: OrderState;
  accepted_at: number;
  delivery_mode: string;
  total_price: string;
  units: FakeOrderUnitRef[];
  lines: FakeOrderLine[];
}

function createSeedOrders(skus: FakeSku[]): FakeOrder[] {
  const now = Math.floor(Date.now() / 1000);
  const unitsOf = (
    name: string,
    finish: Finish,
    condition: Condition,
    status: UnitStatus,
  ): FakeOrderUnitRef[] => {
    const sku = skus.find(
      (candidate) =>
        candidate.name === name &&
        candidate.finish === finish &&
        candidate.condition === condition,
    );
    if (!sku) {
      throw new Error(`missing seed SKU: ${name}`);
    }
    return sku.units
      .filter((unit) => unit.status === status)
      .map((unit) => ({
        sku_id: sku.sku_id,
        sequence_number: unit.sequence_number,
      }));
  };

  const lineOf = (
    units: FakeOrderUnitRef[],
    price: string,
    listedPrice: string | null,
  ): FakeOrderLine => ({
    sku_id: units[0].sku_id,
    quantity: units.length,
    price,
    listed_price: listedPrice,
  });

  const aboveListUnits = {
    signet: unitsOf('Arcane Signet', 'normal', 'NM', 'reserved'),
    rift: unitsOf('Cyclonic Rift', 'normal', 'LP', 'reserved'),
    dockside: unitsOf('Dockside Extortionist', 'normal', 'LP', 'reserved'),
    greaves: unitsOf('Lightning Greaves', 'normal', 'NM', 'reserved'),
    crypt: unitsOf('Mana Crypt', 'normal', 'NM', 'reserved'),
    study: unitsOf('Rhystic Study', 'normal', 'NM', 'reserved'),
  };
  const discountedUnits = {
    solRing: unitsOf('Sol Ring', 'normal', 'NM', 'reserved'),
    aberration: unitsOf('Elvish Aberration', 'normal', 'NM', 'reserved'),
  };
  const atListUnits = unitsOf('Hellkite Tyrant', 'normal', 'NM', 'sold');
  const legacyUnits = unitsOf('Counterspell', 'normal', 'NM', 'in_stock').slice(
    0,
    1,
  );

  return [
    {
      order_id: '83663',
      state: 'awaiting_payment',
      accepted_at: now - 2 * 60 * 60,
      delivery_mode: 'SHIPPING',
      total_price: '479.90',
      units: [
        ...aboveListUnits.signet,
        ...aboveListUnits.rift,
        ...aboveListUnits.dockside,
        ...aboveListUnits.greaves,
        ...aboveListUnits.crypt,
        ...aboveListUnits.study,
      ],
      lines: [
        lineOf(aboveListUnits.signet, '2.00', '1.50'),
        lineOf(aboveListUnits.rift, '90.00', '80.00'),
        lineOf(aboveListUnits.dockside, '90.00', '80.00'),
        lineOf(aboveListUnits.greaves, '12.00', '10.00'),
        lineOf(aboveListUnits.crypt, '220.00', '200.00'),
        lineOf(aboveListUnits.study, '65.90', '60.00'),
      ],
    },
    {
      order_id: '83647',
      state: 'to_pick',
      accepted_at: now - 24 * 60 * 60,
      delivery_mode: 'PICKUP',
      total_price: '10.90',
      units: [...discountedUnits.solRing, ...discountedUnits.aberration],
      lines: [
        lineOf(discountedUnits.solRing, '8.00', '5.00'),
        lineOf(discountedUnits.aberration, '2.90', '3.00'),
      ],
    },
    {
      order_id: '83611',
      state: 'fulfilled',
      accepted_at: now - 3 * 24 * 60 * 60,
      delivery_mode: 'SHIPPING',
      total_price: '8.50',
      units: atListUnits,
      lines: [lineOf(atListUnits, '8.50', '8.50')],
    },
    {
      order_id: '83598',
      state: 'voided',
      accepted_at: now - 5 * 24 * 60 * 60,
      delivery_mode: 'PICKUP',
      total_price: '4.20',
      // the void released this unit back to stock; ingested before listed_price
      units: legacyUnits,
      lines: [lineOf(legacyUnits, '4.20', null)],
    },
  ];
}

function itemsTotalPrice(lines: FakeOrderLine[]): string | null {
  if (lines.length === 0) {
    return null;
  }
  const cents = lines.reduce(
    (sum, line) => sum + Math.round(Number(line.price) * 100),
    0,
  );
  return (cents / 100).toFixed(2);
}

function listedTotalPrice(lines: FakeOrderLine[]): string | null {
  if (lines.length === 0 || lines.some((line) => line.listed_price == null)) {
    return null;
  }
  const cents = lines.reduce(
    (sum, line) =>
      sum + Math.round(Number(line.listed_price) * 100) * line.quantity,
    0,
  );
  return (cents / 100).toFixed(2);
}

function toOrderSummary(order: FakeOrder): OrderSummary {
  return {
    order_id: order.order_id,
    state: order.state,
    accepted_at: order.accepted_at,
    delivery_mode: order.delivery_mode,
    total_price: order.total_price,
    items_total_price: itemsTotalPrice(order.lines),
    listed_total_price: listedTotalPrice(order.lines),
    unit_count: order.units.length,
  };
}

const PUBLISH_SKUS_PER_SECOND = 2;

interface FakePublishRun {
  worklist: string[];
  status: 'running' | 'succeeded';
  started_at_ms: number;
  finished_at_ms: number | null;
  cleared_count: number;
}

function publishedCount(run: FakePublishRun): number {
  if (run.status !== 'running') {
    return run.worklist.length;
  }
  const elapsedMs = Math.max(0, Date.now() - run.started_at_ms);
  return Math.min(
    run.worklist.length,
    Math.floor((elapsedMs / 1000) * PUBLISH_SKUS_PER_SECOND),
  );
}

function toOrderDetail(order: FakeOrder, skus: FakeSku[]): OrderDetail {
  const units: OrderUnit[] = order.units
    .map((ref) => {
      const sku = skus.find((candidate) => candidate.sku_id === ref.sku_id);
      if (!sku) {
        throw new Error('Not Found');
      }
      return {
        sequence_number: ref.sequence_number,
        location: deriveLocation(ref.sequence_number),
        name: sku.name,
        set_code: sku.set_code,
        collector_number: sku.collector_number,
        finish: sku.finish,
        condition: sku.condition,
      };
    })
    .sort((a, b) => a.sequence_number - b.sequence_number);
  const lines: OrderLine[] = order.lines.map((line) => {
    const sku = skus.find((candidate) => candidate.sku_id === line.sku_id);
    if (!sku) {
      throw new Error('Not Found');
    }
    return {
      name: sku.name,
      set_code: sku.set_code,
      collector_number: sku.collector_number,
      finish: sku.finish,
      condition: sku.condition,
      quantity: line.quantity,
      price: line.price,
      listed_price: line.listed_price,
    };
  });
  return { ...toOrderSummary(order), lines, units };
}

export function createFakeClient(): ApiClient {
  const skus = createSeedState();
  const importRecords = createSeedImports();
  const orders = createSeedOrders(skus);
  let importCounter = importRecords.length;
  let photoCounter = 0;
  // seed units occupy sequence numbers 0-599 (blocks A0-A5)
  let nextSequenceNumber = 600;
  // every 4th seed SKU starts dirty so the pending publish badge is non-zero
  const dirtySkuIds = new Set<string>(
    skus.filter((_, index) => index % 4 === 0).map((sku) => sku.sku_id),
  );
  let publishRun: FakePublishRun | null = null;
  let settings: SettingsResponse = {
    credential_set: false,
    updated_at: null,
    track_orders_after: null,
  };
  let reportGeneratedAt = Math.floor(Date.now() / 1000) - 3600;
  let reportStale = false;
  let reportGeneration: GenerationStatus | null = {
    status: 'succeeded',
    error: null,
    started_at: reportGeneratedAt - 100,
    finished_at: reportGeneratedAt,
  };

  const progressPublish = (): void => {
    if (!publishRun) {
      return;
    }
    const published = publishedCount(publishRun);
    // clear each drained SKU only once so later re-dirtied SKUs stay pending
    for (const skuId of publishRun.worklist.slice(
      publishRun.cleared_count,
      published,
    )) {
      dirtySkuIds.delete(skuId);
    }
    publishRun.cleared_count = Math.max(publishRun.cleared_count, published);
    if (
      publishRun.status === 'running' &&
      published >= publishRun.worklist.length
    ) {
      publishRun.status = 'succeeded';
      publishRun.finished_at_ms =
        publishRun.started_at_ms +
        (publishRun.worklist.length / PUBLISH_SKUS_PER_SECOND) * 1000;
    }
  };

  const toPublishResponse = (): PublishResponse => {
    if (!publishRun) {
      return {
        status: null,
        published_sku_count: 0,
        total_sku_count: 0,
        error: null,
        started_at: null,
        finished_at: null,
        pending_sku_count: dirtySkuIds.size,
      };
    }
    return {
      status: publishRun.status,
      published_sku_count: publishedCount(publishRun),
      total_sku_count: publishRun.worklist.length,
      error: null,
      started_at: Math.floor(publishRun.started_at_ms / 1000),
      finished_at:
        publishRun.finished_at_ms === null
          ? null
          : Math.floor(publishRun.finished_at_ms / 1000),
      pending_sku_count: dirtySkuIds.size,
    };
  };

  const getSkuOrThrow = (skuId: string): FakeSku => {
    const sku = skus.find((candidate) => candidate.sku_id === skuId);
    if (!sku) {
      throw new Error('Not Found');
    }
    return sku;
  };

  const getUnitOrThrow = (sku: FakeSku, sequenceNumber: number): FakeUnit => {
    const unit = sku.units.find(
      (candidate) => candidate.sequence_number === sequenceNumber,
    );
    if (!unit) {
      throw new Error('Not Found');
    }
    return unit;
  };

  const getImportOrThrow = (importId: string): FakeImport => {
    const importRecord = importRecords.find(
      (candidate) => candidate.import_id === importId,
    );
    if (!importRecord) {
      throw new Error('Not Found');
    }
    progressAppraisal(importRecord);
    return importRecord;
  };

  const getOrderOrThrow = (orderId: string): FakeOrder => {
    const order = orders.find((candidate) => candidate.order_id === orderId);
    if (!order) {
      throw new Error('Not Found');
    }
    return order;
  };

  return {
    async getSettings(): Promise<SettingsResponse> {
      return { ...settings };
    },

    async updateSettings(
      update: UpdateSettingsRequest,
    ): Promise<SettingsResponse> {
      if (update.refresh_token) {
        settings = {
          ...settings,
          credential_set: true,
          updated_at: Math.floor(Date.now() / 1000),
        };
      }
      if (update.track_orders_after !== undefined) {
        settings = {
          ...settings,
          track_orders_after: update.track_orders_after,
        };
      }
      return { ...settings };
    },

    async createImport(filename: string, csv: string): Promise<ImportSummary> {
      const parsedRows = parseManaBoxCsv(csv);
      // csv row order is physical bottom-up; position 1 is the top of the stack
      const rows: FakeImportRow[] = [];
      let position = 0;
      for (const parsed of [...parsedRows].reverse()) {
        for (let copy = 0; copy < parsed.quantity; copy += 1) {
          position += 1;
          const decided = decideRow(parsed, position);
          rows.push({
            position,
            name: parsed.name,
            set_code: parsed.set_code,
            set_name: parsed.set_name,
            collector_number: parsed.collector_number,
            finish: parsed.finish,
            condition: parsed.condition,
            scryfall_id: parsed.scryfall_id,
            ...decided,
            ...appraisePrices(decided.decision, position),
            photos: [],
          });
        }
      }
      importCounter += 1;
      const importRecord: FakeImport = {
        import_id: `fake-import-${importCounter}`,
        filename,
        status: 'appraising',
        rows,
        created_at_ms: Date.now(),
      };
      importRecords.push(importRecord);
      return toImportSummary(importRecord);
    },

    async findImports(): Promise<FindImportsResponse> {
      const summaries = [...importRecords]
        .sort((a, b) => b.created_at_ms - a.created_at_ms)
        .map((importRecord) => {
          progressAppraisal(importRecord);
          return toImportSummary(importRecord);
        });
      return { imports: summaries, next_continuation: null };
    },

    async getImport(importId: string): Promise<ImportDetail> {
      return toImportDetail(getImportOrThrow(importId));
    },

    async updateImportRow(
      importId: string,
      position: number,
      condition: Condition,
    ): Promise<ImportRow> {
      const importRecord = getImportOrThrow(importId);
      if (importRecord.status !== 'review') {
        throw new Error('import is not in review status');
      }
      const row = importRecord.rows.find((r) => r.position === position);
      if (!row) {
        throw new Error('Not Found');
      }
      row.condition = condition;
      return toImportRow(row, true);
    },

    async deleteImportRow(importId: string, position: number): Promise<void> {
      const importRecord = getImportOrThrow(importId);
      if (importRecord.status !== 'review') {
        throw new Error('import is not in review status');
      }
      const index = importRecord.rows.findIndex((r) => r.position === position);
      if (index === -1) {
        throw new Error('Not Found');
      }
      importRecord.rows.splice(index, 1);
    },

    async addRowPhoto(
      importId: string,
      position: number,
      jpeg: Blob,
    ): Promise<void> {
      const importRecord = getImportOrThrow(importId);
      if (importRecord.status !== 'review') {
        throw new Error('import is not in review status');
      }
      const row = importRecord.rows.find((r) => r.position === position);
      if (!row) {
        throw new Error('Not Found');
      }
      if (row.decision !== 'keep') {
        throw new Error('photos can only be created on keep rows');
      }
      if (row.photos.length >= 5) {
        throw new Error('a row may have at most 5 photos');
      }
      photoCounter += 1;
      row.photos.push({
        photo_id: `fake-photo-${photoCounter}`,
        url: URL.createObjectURL(jpeg),
      });
    },

    async deleteRowPhoto(
      importId: string,
      position: number,
      photoId: string,
    ): Promise<void> {
      const importRecord = getImportOrThrow(importId);
      if (importRecord.status !== 'review') {
        throw new Error('import is not in review status');
      }
      const row = importRecord.rows.find((r) => r.position === position);
      if (!row) {
        throw new Error('Not Found');
      }
      const photoIndex = row.photos.findIndex(
        (photo) => photo.photo_id === photoId,
      );
      if (photoIndex === -1) {
        throw new Error('Not Found');
      }
      const [removed] = row.photos.splice(photoIndex, 1);
      URL.revokeObjectURL(removed.url);
    },

    async deleteImport(importId: string): Promise<void> {
      const importRecord = getImportOrThrow(importId);
      if (importRecord.status !== 'review') {
        throw new Error('import is not in a deletable status');
      }
      const index = importRecords.indexOf(importRecord);
      importRecords.splice(index, 1);
    },

    async confirmImport(importId: string): Promise<ConfirmImportResponse> {
      const importRecord = getImportOrThrow(importId);
      if (importRecord.status !== 'review') {
        throw new Error('import is not in review status');
      }
      // sequence numbers are assigned bottom-up (raw csv order), the reverse of review order
      const keepRows = [...importRecord.rows]
        .sort((a, b) => b.position - a.position)
        .filter((row) => row.decision === 'keep');
      const sequenceNumbers: number[] = [];
      for (const row of keepRows) {
        const sequenceNumber = nextSequenceNumber;
        nextSequenceNumber += 1;
        const skuId = `${row.scryfall_id}#${row.finish}#${row.condition}`;
        let sku = skus.find((candidate) => candidate.sku_id === skuId);
        if (!sku) {
          sku = {
            sku_id: skuId,
            scryfall_id: row.scryfall_id,
            name: row.name,
            set_code: row.set_code,
            set_name: row.set_name,
            collector_number: row.collector_number,
            finish: row.finish,
            condition: row.condition,
            last_published_price: null,
            units: [],
          };
          skus.push(sku);
        }
        sku.units.push({
          sequence_number: sequenceNumber,
          status: 'in_stock',
          photos: row.photos.map((photo) => ({ ...photo })),
        });
        dirtySkuIds.add(sku.sku_id);
        sequenceNumbers.push(sequenceNumber);
      }
      importRecord.status = 'confirmed';
      reportStale = true;

      const first = sequenceNumbers.length > 0 ? sequenceNumbers[0] : null;
      const last =
        sequenceNumbers.length > 0
          ? sequenceNumbers[sequenceNumbers.length - 1]
          : null;
      const placementInstructions: PlacementInstruction[] = [];
      if (first !== null && last !== null) {
        let from = first;
        while (from <= last) {
          const to = Math.min(Math.floor(from / 100) * 100 + 99, last);
          placementInstructions.push({
            block: deriveBlock(from),
            from_location: deriveLocation(from),
            to_location: deriveLocation(to),
            // keepRows[i] received sequence number first + i
            from_name: keepRows[from - first].name,
            to_name: keepRows[to - first].name,
            unit_count: to - from + 1,
          });
          from = to + 1;
        }
      }

      const totalSuggestedCents = keepRows.reduce(
        (sum, row) => sum + Math.round(Number(row.suggested_price) * 100),
        0,
      );

      return {
        import_id: importRecord.import_id,
        status: importRecord.status,
        unit_count: sequenceNumbers.length,
        total_suggested_price: (totalSuggestedCents / 100).toFixed(2),
        first_sequence_number: first,
        last_sequence_number: last,
        placement_instructions: placementInstructions,
      };
    },

    async findSkus(params?: FindSkusParams): Promise<FindSkusResponse> {
      const search = params?.search?.trim().toLowerCase() ?? '';
      const matches = skus
        .map(toSummary)
        .filter((sku) => sku.name.toLowerCase().startsWith(search))
        .sort((a, b) => (browseKey(a) < browseKey(b) ? -1 : 1));
      return { skus: matches, next_continuation: null };
    },

    async getSku(skuId: string): Promise<SkuDetail> {
      return toDetail(getSkuOrThrow(skuId));
    },

    async deleteUnit(skuId: string, sequenceNumber: number): Promise<void> {
      const sku = getSkuOrThrow(skuId);
      const unit = getUnitOrThrow(sku, sequenceNumber);
      if (unit.status !== 'in_stock') {
        throw new Error('unit is not in stock');
      }
      unit.status = 'removed';
      dirtySkuIds.add(sku.sku_id);
      reportStale = true;
    },

    async updateUnit(
      skuId: string,
      sequenceNumber: number,
      condition: Condition,
    ): Promise<UpdateUnitResponse> {
      const sku = getSkuOrThrow(skuId);
      const unit = getUnitOrThrow(sku, sequenceNumber);
      if (unit.status !== 'in_stock') {
        throw new Error('unit is not in stock');
      }
      if (condition === sku.condition) {
        return { sku_id: sku.sku_id };
      }
      const targetSkuId = `${sku.scryfall_id}#${sku.finish}#${condition}`;
      let target = skus.find((candidate) => candidate.sku_id === targetSkuId);
      if (!target) {
        target = {
          ...sku,
          sku_id: targetSkuId,
          condition,
          last_published_price: null,
          units: [],
        };
        skus.push(target);
      }
      sku.units = sku.units.filter((candidate) => candidate !== unit);
      target.units.push({
        sequence_number: unit.sequence_number,
        status: 'in_stock',
        photos: unit.photos.map((photo) => ({ ...photo })),
      });
      dirtySkuIds.add(sku.sku_id);
      dirtySkuIds.add(target.sku_id);
      reportStale = true;
      return { sku_id: targetSkuId };
    },

    async findOrders(): Promise<FindOrdersResponse> {
      const summaries = [...orders]
        .sort((a, b) => b.accepted_at - a.accepted_at)
        .map(toOrderSummary);
      return { orders: summaries };
    },

    async getOrder(orderId: string): Promise<OrderDetail> {
      return toOrderDetail(getOrderOrThrow(orderId), skus);
    },

    async confirmOrder(orderId: string): Promise<OrderDetail> {
      const order = getOrderOrThrow(orderId);
      if (order.state !== 'to_pick') {
        throw new Error('order is not ready to pick');
      }
      for (const ref of order.units) {
        const sku = getSkuOrThrow(ref.sku_id);
        getUnitOrThrow(sku, ref.sequence_number).status = 'sold';
      }
      order.state = 'fulfilled';
      reportStale = true;
      return toOrderDetail(order, skus);
    },

    async createPublish(): Promise<void> {
      progressPublish();
      if (!publishRun || publishRun.status === 'succeeded') {
        publishRun = {
          worklist: [...dirtySkuIds],
          status: 'running',
          started_at_ms: Date.now(),
          finished_at_ms: null,
          cleared_count: 0,
        };
        // an empty worklist completes immediately
        progressPublish();
      }
    },

    async getPublish(): Promise<PublishResponse> {
      progressPublish();
      return toPublishResponse();
    },

    async getReport(): Promise<ReportResponse> {
      const report: Report = {
        totals: {
          inventory_value: '2894.35',
          in_stock_units: 94,
          sku_count: 41,
          reserved_units: 8,
          // consistent with the chart series below: 40 units sold across the
          // visible 12 weeks plus earlier months, revenue equal to the monthly sum
          sold_units: 76,
          revenue_to_date: '1114.75',
          unpriced_units: 0,
        },
        top_sets: [
          {
            set_code: 'cmr',
            set_name: 'Commander Legends',
            in_stock_units: 11,
          },
          {
            set_code: 'sta',
            set_name: 'Strixhaven Mystical Archive',
            in_stock_units: 8,
          },
          { set_code: 'a25', set_name: 'Masters 25', in_stock_units: 5 },
          {
            set_code: 'chk',
            set_name: 'Champions of Kamigawa',
            in_stock_units: 5,
          },
          {
            set_code: 'msc',
            set_name: 'Marvel Super Heroes Commander',
            in_stock_units: 4,
          },
          { set_code: 'dom', set_name: 'Dominaria', in_stock_units: 3 },
          { set_code: 'mh2', set_name: 'Modern Horizons 2', in_stock_units: 3 },
          { set_code: 'ema', set_name: 'Eternal Masters', in_stock_units: 2 },
          { set_code: 'm12', set_name: 'Magic 2012', in_stock_units: 2 },
          { set_code: 'cmm', set_name: 'Commander Masters', in_stock_units: 1 },
        ],
        price_buckets: [
          { label: '$0.25-$0.50', in_stock_units: 38 },
          { label: '$0.50-$1', in_stock_units: 24 },
          { label: '$1-$2', in_stock_units: 15 },
          { label: '$2-$5', in_stock_units: 9 },
          { label: '$5-$10', in_stock_units: 5 },
          { label: '$10+', in_stock_units: 3 },
        ],
        top_hits: [
          {
            sku_id: '29ba5a2d-d787-4214-8cd7-7f2bcea938f8#normal#NM',
            name: 'Doubling Season',
            set_code: 'bbd',
            collector_number: '195',
            finish: 'normal',
            condition: 'NM',
            price: '95.00',
            in_stock_units: 1,
          },
          {
            sku_id: 'd5b5d2a7-8185-4df0-a35a-f89c12857f87#normal#LP',
            name: 'Sylvan Library',
            set_code: 'ema',
            collector_number: '187',
            finish: 'normal',
            condition: 'LP',
            price: '48.50',
            in_stock_units: 1,
          },
          {
            sku_id: '205c4689-8b02-4d40-9274-3c1fcafa8b82#normal#LP',
            name: 'Cyclonic Rift',
            set_code: 'rtr',
            collector_number: '35',
            finish: 'normal',
            condition: 'LP',
            price: '32.00',
            in_stock_units: 2,
          },
          {
            sku_id: 'e9be371c-c688-44ad-ab71-bd4c9f242d58#foil#NM',
            name: 'Force of Negation',
            set_code: 'mh1',
            collector_number: '52',
            finish: 'foil',
            condition: 'NM',
            price: '28.75',
            in_stock_units: 1,
          },
          {
            sku_id: 'd6914dba-0d27-4055-ac34-b3ebf5802221#normal#NM',
            name: 'Rhystic Study',
            set_code: 'jmp',
            collector_number: '169',
            finish: 'normal',
            condition: 'NM',
            price: '18.50',
            in_stock_units: 2,
          },
        ],
        aging_bands: [
          { label: '0-30 days', in_stock_units: 22 },
          { label: '31-90 days', in_stock_units: 35 },
          { label: '91-180 days', in_stock_units: 25 },
          { label: '180+ days', in_stock_units: 12 },
        ],
        revenue_by_month: [
          { month: '2026-03', revenue: '124.50', order_count: 8 },
          { month: '2026-04', revenue: '287.00', order_count: 15 },
          { month: '2026-05', revenue: '195.75', order_count: 12 },
          { month: '2026-06', revenue: '342.20', order_count: 18 },
          { month: '2026-07', revenue: '156.80', order_count: 9 },
          { month: '2026-08', revenue: '8.50', order_count: 1 },
        ],
        intake_vs_sales_by_week: [
          { week_start: '2026-06-01', added_units: 12, sold_units: 3 },
          { week_start: '2026-06-08', added_units: 8, sold_units: 5 },
          { week_start: '2026-06-15', added_units: 15, sold_units: 4 },
          { week_start: '2026-06-22', added_units: 6, sold_units: 7 },
          { week_start: '2026-06-29', added_units: 10, sold_units: 2 },
          { week_start: '2026-07-06', added_units: 14, sold_units: 6 },
          { week_start: '2026-07-13', added_units: 9, sold_units: 3 },
          { week_start: '2026-07-20', added_units: 11, sold_units: 4 },
          { week_start: '2026-07-27', added_units: 7, sold_units: 5 },
          { week_start: '2026-08-03', added_units: 18, sold_units: 1 },
          { week_start: '2026-08-10', added_units: 5, sold_units: 0 },
          { week_start: '2026-08-17', added_units: 3, sold_units: 0 },
        ],
      };
      return {
        generated_at: reportGeneratedAt,
        stale: reportStale,
        generation: reportGeneration,
        report,
      };
    },

    async createReport(): Promise<void> {
      reportGeneration = {
        status: 'queued',
        error: null,
        started_at: Math.floor(Date.now() / 1000),
        finished_at: null,
      };
      setTimeout(() => {
        reportGeneratedAt = Math.floor(Date.now() / 1000);
        reportStale = false;
        reportGeneration = {
          status: 'succeeded',
          error: null,
          started_at: reportGeneration!.started_at,
          finished_at: reportGeneratedAt,
        };
      }, 3000);
    },
  };
}
