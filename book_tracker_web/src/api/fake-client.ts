import { getSession } from '../auth/session';
import { ApiError } from './client';
import type {
  ApiClient,
  Book,
  CreateBookRequest,
  CreateBookResponse,
  GetBookResponse,
  GetBooksResponse,
  UpdateBookRequest,
  UpdateBookResponse,
} from './client';

const seededBooks: Book[] = [
  {
    open_library_work_id: 'OL27448W',
    title: 'The Lord of the Rings',
    authors: ['J.R.R. Tolkien'],
    cover_url: 'https://covers.openlibrary.org/b/id/14625765-L.jpg',
    page_count: 1193,
    publication_year: 1954,
    finished_date: '2026-04-28',
    created_at: 1714809600,
    updated_at: 1714809600,
  },
  {
    open_library_work_id: 'OL45804W',
    title: 'Pride and Prejudice',
    authors: ['Jane Austen'],
    cover_url: 'https://covers.openlibrary.org/b/id/12645114-L.jpg',
    page_count: 432,
    publication_year: 1813,
    finished_date: '2026-04-15',
    created_at: 1713600000,
    updated_at: 1713600000,
  },
  {
    open_library_work_id: 'OL76837W',
    title: 'Beloved',
    authors: ['Toni Morrison'],
    cover_url: 'https://covers.openlibrary.org/b/id/8231861-L.jpg',
    page_count: 324,
    publication_year: 1987,
    finished_date: '2026-04-04',
    created_at: 1712275200,
    updated_at: 1712275200,
  },
  {
    open_library_work_id: 'OL14933414W',
    title: 'Project Hail Mary',
    authors: ['Andy Weir'],
    cover_url: 'https://covers.openlibrary.org/b/id/12100107-L.jpg',
    page_count: 476,
    publication_year: 2021,
    finished_date: '2026-03-26',
    created_at: 1711411200,
    updated_at: 1711411200,
  },
  {
    open_library_work_id: 'OL15275569W',
    title: 'Klara and the Sun',
    authors: ['Kazuo Ishiguro'],
    cover_url: 'https://covers.openlibrary.org/b/id/10523452-L.jpg',
    page_count: 303,
    publication_year: 2021,
    finished_date: '2026-03-12',
    created_at: 1710201600,
    updated_at: 1710201600,
  },
  {
    open_library_work_id: 'OL15931437W',
    title: 'Piranesi',
    authors: ['Susanna Clarke'],
    cover_url: 'https://covers.openlibrary.org/b/id/10590363-L.jpg',
    page_count: 245,
    publication_year: 2020,
    finished_date: '2026-03-02',
    created_at: 1709337600,
    updated_at: 1709337600,
  },
  {
    open_library_work_id: 'OL17014439W',
    title: 'A Gentleman in Moscow',
    authors: ['Amor Towles'],
    cover_url: 'https://covers.openlibrary.org/b/id/8311557-L.jpg',
    page_count: 462,
    publication_year: 2016,
    finished_date: '2026-02-22',
    created_at: 1708560000,
    updated_at: 1708560000,
  },
  {
    open_library_work_id: 'OL19921121W',
    title: 'Educated',
    authors: ['Tara Westover'],
    cover_url: 'https://covers.openlibrary.org/b/id/8732887-L.jpg',
    page_count: 334,
    publication_year: 2018,
    finished_date: '2026-02-08',
    created_at: 1707350400,
    updated_at: 1707350400,
  },
  {
    open_library_work_id: 'OL20920057W',
    title: 'The Overstory',
    authors: ['Richard Powers'],
    cover_url: 'https://covers.openlibrary.org/b/id/9255566-L.jpg',
    page_count: 502,
    publication_year: 2018,
    finished_date: '2026-01-25',
    created_at: 1706140800,
    updated_at: 1706140800,
  },
  {
    open_library_work_id: 'OL27978087W',
    title: 'Tomorrow, and Tomorrow, and Tomorrow',
    authors: ['Gabrielle Zevin'],
    cover_url: 'https://covers.openlibrary.org/b/id/13193943-L.jpg',
    page_count: 416,
    publication_year: 2022,
    finished_date: '2026-01-11',
    created_at: 1704931200,
    updated_at: 1704931200,
  },
];

const ROLLING_WINDOW_DAYS = 365;

const books: Book[] = seededBooks.map((book) => ({ ...book }));

function compareBooks(a: Book, b: Book): number {
  if (a.finished_date !== b.finished_date) {
    return b.finished_date.localeCompare(a.finished_date);
  }
  return a.open_library_work_id.localeCompare(b.open_library_work_id);
}

function computeRollingCount(): number {
  const now = new Date();
  const cutoff = new Date(now);
  cutoff.setUTCDate(cutoff.getUTCDate() - ROLLING_WINDOW_DAYS);
  const cutoffString = cutoff.toISOString().slice(0, 10);
  return books.filter((book) => book.finished_date >= cutoffString).length;
}

export function createFakeClient(): ApiClient {
  return {
    async getBooks(): Promise<GetBooksResponse> {
      const session = getSession();
      if (!session) {
        throw new ApiError(401, 'Not authenticated');
      }

      const sorted = [...books].sort(compareBooks);
      return {
        books: sorted,
        rolling_12_month_count: computeRollingCount(),
      };
    },

    async getBook(openLibraryWorkId: string): Promise<GetBookResponse> {
      const session = getSession();
      if (!session) {
        throw new ApiError(401, 'Not authenticated');
      }

      const book = books.find(
        (b) => b.open_library_work_id === openLibraryWorkId,
      );
      if (!book) {
        throw new ApiError(404, 'Not Found');
      }
      return { book };
    },

    async createBook(
      request: CreateBookRequest,
    ): Promise<CreateBookResponse> {
      const session = getSession();
      if (!session) {
        throw new ApiError(401, 'Not authenticated');
      }

      const existing = books.find(
        (b) => b.open_library_work_id === request.open_library_work_id,
      );
      if (existing) {
        throw new ApiError(
          409,
          `already added on ${existing.finished_date}`,
        );
      }

      const now = Math.floor(Date.now() / 1000);
      const book: Book = {
        open_library_work_id: request.open_library_work_id,
        title: request.title,
        authors: request.authors,
        cover_url: request.cover_url,
        page_count: request.page_count,
        publication_year: request.publication_year,
        finished_date: request.finished_date,
        created_at: now,
        updated_at: now,
      };

      books.push(book);
      return { book };
    },

    async updateBook(
      openLibraryWorkId: string,
      request: UpdateBookRequest,
    ): Promise<UpdateBookResponse> {
      const session = getSession();
      if (!session) {
        throw new ApiError(401, 'Not authenticated');
      }

      const index = books.findIndex(
        (b) => b.open_library_work_id === openLibraryWorkId,
      );
      if (index === -1) {
        throw new ApiError(404, 'Not Found');
      }

      const existing = books[index];
      const now = Math.floor(Date.now() / 1000);
      const updated: Book = {
        ...existing,
        finished_date: request.finished_date,
        updated_at: now,
      };
      books[index] = updated;
      return { book: updated };
    },

    async deleteBook(openLibraryWorkId: string): Promise<void> {
      const session = getSession();
      if (!session) {
        throw new ApiError(401, 'Not authenticated');
      }

      const index = books.findIndex(
        (b) => b.open_library_work_id === openLibraryWorkId,
      );
      if (index === -1) {
        throw new ApiError(404, 'Not Found');
      }
      books.splice(index, 1);
    },
  };
}
