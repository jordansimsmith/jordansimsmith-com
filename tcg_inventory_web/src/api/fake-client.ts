import type {
  ApiClient,
  Condition,
  Finish,
  FindSkusParams,
  FindSkusResponse,
  SettingsResponse,
  SkuDetail,
  SkuSummary,
  SkuUnit,
  UnitStatus,
  UpdateUnitResponse,
} from './client';

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
  ['0bc3401f-935b-45ce-b1e6-300a5d9dfd4f', 'Hellkite Tyrant', 'gtc', 'Gatecrash', '94', 'normal', 'NM', 1, 0],
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
}

interface FakeSku {
  sku_id: string;
  scryfall_id: string;
  name: string;
  set_code: string;
  set_name: string;
  collector_number: string;
  finish: Finish;
  condition: Condition;
  units: FakeUnit[];
}

function createSeedState(): FakeSku[] {
  let unitIndex = 0;
  return seedSkus.map(
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
    ]) => {
      const units: FakeUnit[] = [];
      for (let i = 0; i < inStockCount + reservedCount; i += 1) {
        // stride 37 is coprime with 600, scattering units across blocks A0-A5
        units.push({
          sequence_number: (unitIndex * 37) % 600,
          status: 'in_stock',
        });
        unitIndex += 1;
      }
      units.sort((a, b) => a.sequence_number - b.sequence_number);
      // reservations take the forward-most units
      for (let i = 0; i < reservedCount; i += 1) {
        units[i].status = 'reserved';
      }
      return {
        sku_id: `${scryfallId}#${finish}#${condition}`,
        scryfall_id: scryfallId,
        name,
        set_code: setCode,
        set_name: setName,
        collector_number: collectorNumber,
        finish,
        condition,
        units,
      };
    },
  );
}

function deriveLocation(sequenceNumber: number): string {
  const block = Math.floor(sequenceNumber / 100);
  const letter = String.fromCharCode(
    'A'.charCodeAt(0) + Math.floor(block / 100),
  );
  return `${letter}${block % 100}-${sequenceNumber % 100}`;
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
    in_stock_count: countByStatus(sku, 'in_stock'),
    reserved_count: countByStatus(sku, 'reserved'),
  };
}

function toDetail(sku: FakeSku): SkuDetail {
  const units: SkuUnit[] = [...sku.units]
    .sort((a, b) => a.sequence_number - b.sequence_number)
    .map((unit) => ({
      sequence_number: unit.sequence_number,
      location: deriveLocation(unit.sequence_number),
      status: unit.status,
    }));
  return {
    ...toSummary(sku),
    scryfall_id: sku.scryfall_id,
    sold_count: countByStatus(sku, 'sold'),
    units,
  };
}

function browseKey(sku: SkuSummary): string {
  return `${sku.name.toLowerCase()}#${sku.sku_id}`;
}

export function createFakeClient(): ApiClient {
  const skus = createSeedState();

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

  return {
    async getSettings(): Promise<SettingsResponse> {
      return { credential_set: false, updated_at: null };
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

    async deleteUnit(
      skuId: string,
      sequenceNumber: number,
    ): Promise<SkuDetail> {
      const sku = getSkuOrThrow(skuId);
      const unit = getUnitOrThrow(sku, sequenceNumber);
      if (unit.status !== 'in_stock') {
        throw new Error('unit is not in stock');
      }
      unit.status = 'removed';
      return toDetail(sku);
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
        target = { ...sku, sku_id: targetSkuId, condition, units: [] };
        skus.push(target);
      }
      sku.units = sku.units.filter((candidate) => candidate !== unit);
      target.units.push({
        sequence_number: unit.sequence_number,
        status: 'in_stock',
      });
      return { sku_id: targetSkuId };
    },
  };
}
