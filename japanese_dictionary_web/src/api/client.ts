import { createFakeClient } from './fake-client';
import { createHttpClient } from './http-client';

export interface SCElement {
  tag: string;
  content?: SCNode;
  data?: Record<string, string>;
  href?: string;
  src?: string;
  alt?: string;
  title?: string;
  lang?: string;
  width?: number;
  height?: number;
  [key: string]: unknown;
}

export type SCNode = string | SCNode[] | SCElement;

export interface SearchResult {
  sequence: number;
  expression: string;
  reading: string;
  reading_romaji: string;
  frequency_rank: number | null;
  pitch: number | null;
  glossary_raw: SCNode;
}

export interface SearchResponse {
  results: SearchResult[];
}

export interface BookmarksResponse {
  sequences: number[];
}

export interface ApiClient {
  search(q: string): Promise<SearchResponse>;
  findBookmarks(): Promise<BookmarksResponse>;
  createBookmark(sequence: number): Promise<void>;
  deleteBookmark(sequence: number): Promise<void>;
}

export const apiClient: ApiClient = import.meta.env.PROD
  ? createHttpClient()
  : createFakeClient();
