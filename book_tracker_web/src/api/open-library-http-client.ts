import type {
  OpenLibraryClient,
  OpenLibrarySearchResponse,
  OpenLibrarySearchResult,
} from './open-library-client';

const BASE_URL =
  import.meta.env.VITE_OPEN_LIBRARY_BASE_URL || 'https://openlibrary.org';

const SEARCH_FIELDS = [
  'key',
  'title',
  'author_name',
  'cover_i',
  'first_publish_year',
  'number_of_pages_median',
  'edition_count',
].join(',');

const WORK_ID_PATTERN = /^\/works\/(OL[0-9]+W)$/;

interface OpenLibrarySearchDoc {
  key?: string;
  title?: string;
  author_name?: string[];
  cover_i?: number;
  first_publish_year?: number;
  number_of_pages_median?: number;
  edition_count?: number;
}

interface OpenLibrarySearchPayload {
  docs?: OpenLibrarySearchDoc[];
}

function toResult(doc: OpenLibrarySearchDoc): OpenLibrarySearchResult | null {
  if (!doc.key || !doc.title) {
    return null;
  }
  const match = doc.key.match(WORK_ID_PATTERN);
  if (!match) {
    return null;
  }
  return {
    open_library_work_id: match[1],
    title: doc.title,
    author_names: doc.author_name ?? [],
    cover_id: doc.cover_i ?? null,
    first_publish_year: doc.first_publish_year ?? null,
    number_of_pages_median: doc.number_of_pages_median ?? null,
    edition_count: doc.edition_count ?? null,
  };
}

export function createOpenLibraryHttpClient(): OpenLibraryClient {
  return {
    async search(query: string): Promise<OpenLibrarySearchResponse> {
      const params = new URLSearchParams({
        title: query,
        limit: '10',
        fields: SEARCH_FIELDS,
      });
      const response = await fetch(`${BASE_URL}/search.json?${params}`);
      if (!response.ok) {
        throw new Error(`Open Library search failed: ${response.statusText}`);
      }

      const payload = (await response.json()) as OpenLibrarySearchPayload;
      const docs = payload.docs ?? [];
      const results = docs
        .map(toResult)
        .filter((r): r is OpenLibrarySearchResult => r !== null);

      return { results };
    },
  };
}
