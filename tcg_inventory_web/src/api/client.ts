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
  last_published_price: string | null;
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
  in_stock_count: number;
  reserved_count: number;
  sold_count: number;
  units: SkuUnit[];
}

export interface UpdateUnitResponse {
  sku_id: string;
}

export type ImportStatus = 'appraising' | 'review' | 'confirming' | 'confirmed';

export type RowDecision = 'keep' | 'discard' | 'review';

export interface ImportSummary {
  import_id: string;
  filename: string;
  status: ImportStatus;
  row_count: number;
  appraisal_error: string | null;
  created_at: number;
}

export interface FindImportsResponse {
  imports: ImportSummary[];
}

export interface ImportRow {
  position: number;
  name: string;
  set_code: string;
  set_name: string;
  collector_number: string;
  finish: Finish;
  condition: Condition;
  scryfall_id: string;
  decision: RowDecision | null;
  decision_reason: string | null;
  market_price: string | null;
  suggested_price: string | null;
}

export interface ImportDetail extends ImportSummary {
  rows: ImportRow[];
}

export interface PlacementInstruction {
  block: string;
  from_location: string;
  to_location: string;
  from_name: string;
  to_name: string;
  unit_count: number;
}

export interface ConfirmImportResponse {
  import_id: string;
  status: ImportStatus;
  unit_count: number;
  first_sequence_number: number | null;
  last_sequence_number: number | null;
  placement_instructions: PlacementInstruction[];
}

export type OrderState =
  | 'awaiting_payment'
  | 'to_pick'
  | 'fulfilled'
  | 'voided';

export interface OrderSummary {
  order_id: string;
  state: OrderState;
  accepted_at: number;
  delivery_mode: string;
  total_price: string;
  unit_count: number;
}

export interface FindOrdersResponse {
  orders: OrderSummary[];
}

export interface OrderUnit {
  sequence_number: number;
  location: string;
  name: string;
  set_code: string;
  collector_number: string;
  finish: Finish;
  condition: Condition;
}

export interface OrderDetail extends OrderSummary {
  units: OrderUnit[];
}

export type PublishRunStatus = 'queued' | 'running' | 'succeeded' | 'failed';

export interface PublishResponse {
  status: PublishRunStatus | null;
  published_sku_count: number;
  total_sku_count: number;
  error: string | null;
  started_at: number | null;
  finished_at: number | null;
  pending_sku_count: number;
}

export interface ApiClient {
  getSettings(): Promise<SettingsResponse>;
  updateSettings(refreshToken: string): Promise<SettingsResponse>;
  createImport(filename: string, csv: string): Promise<ImportSummary>;
  findImports(): Promise<FindImportsResponse>;
  getImport(importId: string): Promise<ImportDetail>;
  updateImportRow(
    importId: string,
    position: number,
    condition: Condition,
  ): Promise<ImportRow>;
  deleteImportRow(importId: string, position: number): Promise<void>;
  confirmImport(importId: string): Promise<ConfirmImportResponse>;
  findSkus(params?: FindSkusParams): Promise<FindSkusResponse>;
  getSku(skuId: string): Promise<SkuDetail>;
  deleteUnit(
    skuId: string,
    sequenceNumber: number,
    reason?: string,
  ): Promise<void>;
  updateUnit(
    skuId: string,
    sequenceNumber: number,
    condition: Condition,
  ): Promise<UpdateUnitResponse>;
  findOrders(): Promise<FindOrdersResponse>;
  getOrder(orderId: string): Promise<OrderDetail>;
  confirmOrder(orderId: string): Promise<OrderDetail>;
  createPublish(): Promise<void>;
  getPublish(): Promise<PublishResponse>;
}

export const apiClient: ApiClient = import.meta.env.PROD
  ? createHttpClient()
  : createFakeClient();
