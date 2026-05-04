import { createOpenLibraryFakeClient } from './open-library-fake-client';
import { createOpenLibraryHttpClient } from './open-library-http-client';

export interface OpenLibrarySearchResult {
  open_library_work_id: string;
  title: string;
  author_names: string[];
  cover_id: number | null;
  first_publish_year: number | null;
  number_of_pages_median: number | null;
  edition_count: number | null;
}

export interface OpenLibrarySearchResponse {
  results: OpenLibrarySearchResult[];
}

export interface OpenLibraryClient {
  search(query: string): Promise<OpenLibrarySearchResponse>;
}

export const openLibraryClient: OpenLibraryClient = import.meta.env.PROD
  ? createOpenLibraryHttpClient()
  : createOpenLibraryFakeClient();
