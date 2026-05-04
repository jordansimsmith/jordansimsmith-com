import type {
  OpenLibraryClient,
  OpenLibrarySearchResponse,
  OpenLibrarySearchResult,
} from './open-library-client';

const seededResults: OpenLibrarySearchResult[] = [
  {
    open_library_work_id: 'OL14854528W',
    title: 'The Name of the Wind',
    author_names: ['Patrick Rothfuss'],
    cover_id: 14627509,
    first_publish_year: 2007,
    number_of_pages_median: 662,
    edition_count: 124,
  },
  {
    open_library_work_id: 'OL14855137W',
    title: "The Wise Man's Fear",
    author_names: ['Patrick Rothfuss'],
    cover_id: 12347213,
    first_publish_year: 2011,
    number_of_pages_median: 994,
    edition_count: 71,
  },
  {
    open_library_work_id: 'OL19729004W',
    title: 'The Fifth Season',
    author_names: ['N.K. Jemisin'],
    cover_id: 8267078,
    first_publish_year: 2015,
    number_of_pages_median: 512,
    edition_count: 27,
  },
  {
    open_library_work_id: 'OL15945825W',
    title: 'Station Eleven',
    author_names: ['Emily St. John Mandel'],
    cover_id: 8231902,
    first_publish_year: 2014,
    number_of_pages_median: 333,
    edition_count: 79,
  },
  {
    open_library_work_id: 'OL17707030W',
    title: 'Circe',
    author_names: ['Madeline Miller'],
    cover_id: 8472907,
    first_publish_year: 2018,
    number_of_pages_median: 393,
    edition_count: 47,
  },
  {
    open_library_work_id: 'OL15834615W',
    title: 'The Goldfinch',
    author_names: ['Donna Tartt'],
    cover_id: 7416349,
    first_publish_year: 2013,
    number_of_pages_median: 771,
    edition_count: 102,
  },
  {
    open_library_work_id: 'OL19764460W',
    title: 'Hidden Figures',
    author_names: ['Margot Lee Shetterly'],
    cover_id: 8313507,
    first_publish_year: 2016,
    number_of_pages_median: 368,
    edition_count: 35,
  },
  {
    open_library_work_id: 'OL16800549W',
    title: 'Born a Crime',
    author_names: ['Trevor Noah'],
    cover_id: 8302225,
    first_publish_year: 2016,
    number_of_pages_median: 304,
    edition_count: 41,
  },
  {
    open_library_work_id: 'OL17460982W',
    title: 'The Three-Body Problem',
    author_names: ['Liu Cixin'],
    cover_id: 8231856,
    first_publish_year: 2008,
    number_of_pages_median: 415,
    edition_count: 62,
  },
  {
    open_library_work_id: 'OL16780236W',
    title: 'Sapiens: A Brief History of Humankind',
    author_names: ['Yuval Noah Harari'],
    cover_id: 7965074,
    first_publish_year: 2011,
    number_of_pages_median: 443,
    edition_count: 89,
  },
  {
    open_library_work_id: 'OL31362573W',
    title: 'No Cover Adventures',
    author_names: ['Anonymous'],
    cover_id: null,
    first_publish_year: 2024,
    number_of_pages_median: null,
    edition_count: 1,
  },
];

function matchesQuery(
  result: OpenLibrarySearchResult,
  normalizedQuery: string,
): boolean {
  if (result.title.toLowerCase().includes(normalizedQuery)) {
    return true;
  }
  return result.author_names.some((author) =>
    author.toLowerCase().includes(normalizedQuery),
  );
}

export function createOpenLibraryFakeClient(): OpenLibraryClient {
  return {
    async search(query: string): Promise<OpenLibrarySearchResponse> {
      const trimmed = query.trim().toLowerCase();
      if (!trimmed) {
        return { results: [] };
      }
      const matching = seededResults.filter((result) =>
        matchesQuery(result, trimmed),
      );
      return { results: matching.slice(0, 10) };
    },
  };
}
