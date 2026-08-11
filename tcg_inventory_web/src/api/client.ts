import { createFakeClient } from './fake-client';
import { createHttpClient } from './http-client';

export interface SettingsResponse {
  credential_set: boolean;
  updated_at: number | null;
}

export type Finish = 'normal' | 'foil' | 'etched';

export type Condition = 'NM' | 'LP' | 'MP' | 'HP' | 'DMG';

export const CONDITIONS: Condition[] = ['NM', 'LP', 'MP', 'HP', 'DMG'];

export type UnitStatus = 'in_stock' | 'reserved' | 'sold' | 'removed';

export interface SkuSummary {
  sku_id: string;
  name: string;
  set_code: string;
  set_name: string;
  collector_number: string;
  finish: Finish;
  condition: Condition;
  in_stock_count: number;
  reserved_count: number;
}

export interface FindSkusResponse {
  skus: SkuSummary[];
  next_continuation: string | null;
}

export interface FindSkusParams {
  search?: string;
  continuation?: string;
}

export interface SkuUnit {
  sequence_number: number;
  location: string;
  status: UnitStatus;
}

export interface SkuDetail extends SkuSummary {
  scryfall_id: string;
  sold_count: number;
  units: SkuUnit[];
}

export interface UpdateUnitResponse {
  sku_id: string;
}

export interface ApiClient {
  getSettings(): Promise<SettingsResponse>;
  findSkus(params?: FindSkusParams): Promise<FindSkusResponse>;
  getSku(skuId: string): Promise<SkuDetail>;
  deleteUnit(
    skuId: string,
    sequenceNumber: number,
    reason?: string,
  ): Promise<SkuDetail>;
  updateUnit(
    skuId: string,
    sequenceNumber: number,
    condition: Condition,
  ): Promise<UpdateUnitResponse>;
}

export const apiClient: ApiClient = import.meta.env.PROD
  ? createHttpClient()
  : createFakeClient();
