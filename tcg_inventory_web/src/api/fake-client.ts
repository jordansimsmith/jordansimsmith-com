import type {
  ApiClient,
  Condition,
  Finish,
  FindSkusParams,
  FindSkusResponse,
  SettingsResponse,
  SkuSummary,
} from './client';

type SeedSku = [
  printing: number,
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
  [1, 'Ancient Tomb', 'uma', 'Ultimate Masters', '236', 'normal', 'LP', 1, 0],
  [2, 'Arcane Signet', 'c21', 'Commander 2021', '237', 'normal', 'NM', 5, 1],
  [3, 'Birds of Paradise', 'm12', 'Magic 2012', '165', 'normal', 'MP', 2, 0],
  [4, 'Blightsteel Colossus', 'som', 'Scars of Mirrodin', '99', 'normal', 'NM', 1, 0],
  [5, 'Brainstorm', 'ema', 'Eternal Masters', '40', 'normal', 'NM', 4, 0],
  [6, 'Counterspell', 'mh2', 'Modern Horizons 2', '267', 'normal', 'NM', 3, 0],
  [6, 'Counterspell', 'mh2', 'Modern Horizons 2', '267', 'foil', 'NM', 1, 0],
  [7, 'Cultivate', 'm21', 'Core Set 2021', '177', 'normal', 'NM', 6, 0],
  [8, 'Cyclonic Rift', 'rtr', 'Return to Ravnica', '35', 'normal', 'LP', 2, 1],
  [9, 'Dark Ritual', 'a25', 'Masters 25', '82', 'normal', 'NM', 3, 0],
  [10, 'Demonic Tutor', 'dmr', 'Dominaria Remastered', '90', 'normal', 'MP', 1, 0],
  [11, 'Dockside Extortionist', 'c19', 'Commander 2019', '24', 'normal', 'LP', 1, 1],
  [12, 'Doubling Season', 'bbd', 'Battlebond', '63', 'normal', 'NM', 1, 0],
  [13, 'Elvish Aberration', 'a25', 'Masters 25', '167', 'normal', 'NM', 2, 1],
  [14, 'Elvish Mystic', 'ktk', 'Khans of Tarkir', '132', 'normal', 'NM', 6, 0],
  [15, 'Eternal Witness', 'mh2', 'Modern Horizons 2', '155', 'normal', 'NM', 3, 0],
  [16, 'Fabled Passage', 'eld', 'Throne of Eldraine', '244', 'normal', 'NM', 2, 0],
  [17, 'Farseek', 'rtr', 'Return to Ravnica', '123', 'normal', 'LP', 4, 0],
  [18, 'Fellwar Stone', 'c21', 'Commander 2021', '244', 'normal', 'MP', 3, 0],
  [19, 'Force of Negation', 'mh1', 'Modern Horizons', '52', 'foil', 'NM', 1, 0],
  [20, 'Ghostly Prison', 'chk', 'Champions of Kamigawa', '10', 'normal', 'HP', 0, 0],
  [21, 'Goblin Guide', 'zen', 'Zendikar', '145', 'normal', 'DMG', 1, 0],
  [22, 'Hellkite Tyrant', 'gtc', 'Gatecrash', '75', 'normal', 'NM', 1, 0],
  [23, 'Jeska, Thrice Reborn', 'cmr', 'Commander Legends', '472', 'etched', 'NM', 1, 0],
  [24, "Kodama's Reach", 'chk', 'Champions of Kamigawa', '211', 'normal', 'MP', 5, 0],
  [25, 'Lightning Bolt', 'sta', 'Strixhaven Mystical Archive', '42', 'normal', 'NM', 4, 0],
  [25, 'Lightning Bolt', 'sta', 'Strixhaven Mystical Archive', '42', 'normal', 'LP', 2, 0],
  [26, 'Lightning Greaves', 'cmr', 'Commander Legends', '461', 'normal', 'NM', 3, 1],
  [27, 'Llanowar Elves', 'dom', 'Dominaria', '168', 'normal', 'NM', 6, 0],
  [28, 'Mana Crypt', '2xm', 'Double Masters', '270', 'normal', 'NM', 0, 1],
  [29, 'Mystic Remora', 'cmr', 'Commander Legends', '395', 'normal', 'LP', 2, 0],
  [30, 'Opt', 'dom', 'Dominaria', '60', 'normal', 'NM', 5, 0],
  [31, 'Path to Exile', 'mm3', 'Modern Masters 2017', '24', 'normal', 'NM', 3, 0],
  [32, 'Ponder', 'm12', 'Magic 2012', '71', 'normal', 'HP', 1, 0],
  [33, 'Rhystic Study', 'jmp', 'Jumpstart', '169', 'normal', 'NM', 2, 1],
  [34, 'Sakura-Tribe Elder', 'chk', 'Champions of Kamigawa', '245', 'normal', 'NM', 4, 0],
  [35, 'Sol Ring', 'cmr', 'Commander Legends', '331', 'normal', 'NM', 6, 2],
  [35, 'Sol Ring', 'cmr', 'Commander Legends', '331', 'normal', 'MP', 2, 0],
  [36, 'Swiftfoot Boots', 'cmr', 'Commander Legends', '469', 'normal', 'NM', 3, 0],
  [37, 'Swords to Plowshares', 'sta', 'Strixhaven Mystical Archive', '10', 'normal', 'NM', 2, 0],
  [38, 'Sylvan Library', 'ema', 'Eternal Masters', '185', 'normal', 'LP', 1, 0],
];

function createSeedSkus(): SkuSummary[] {
  return seedSkus.map(
    ([
      printing,
      name,
      setCode,
      setName,
      collectorNumber,
      finish,
      condition,
      inStockCount,
      reservedCount,
    ]) => {
      const scryfallId = `00000000-0000-4000-8000-${String(printing).padStart(12, '0')}`;
      return {
        sku_id: `${scryfallId}#${finish}#${condition}`,
        name,
        set_code: setCode,
        set_name: setName,
        collector_number: collectorNumber,
        finish,
        condition,
        in_stock_count: inStockCount,
        reserved_count: reservedCount,
      };
    },
  );
}

function browseKey(sku: SkuSummary): string {
  return `${sku.name.toLowerCase()}#${sku.sku_id}`;
}

export function createFakeClient(): ApiClient {
  const skus = createSeedSkus();

  return {
    async getSettings(): Promise<SettingsResponse> {
      return { credential_set: false, updated_at: null };
    },

    async findSkus(params?: FindSkusParams): Promise<FindSkusResponse> {
      const search = params?.search?.trim().toLowerCase() ?? '';
      const matches = skus
        .filter((sku) => sku.name.toLowerCase().startsWith(search))
        .sort((a, b) => (browseKey(a) < browseKey(b) ? -1 : 1));
      return { skus: matches, next_continuation: null };
    },
  };
}
