import { createFakeClient } from './fake-client';
import { createHttpClient } from './http-client';

export interface Book {
  open_library_work_id: string;
  title: string;
  authors: string[];
  cover_url: string | null;
  page_count: number | null;
  publication_year: number | null;
  finished_date: string;
  created_at: number;
  updated_at: number;
}

export interface GetBooksResponse {
  books: Book[];
  rolling_12_month_count: number;
}

export interface GetBookResponse {
  book: Book;
}

export interface CreateBookRequest {
  open_library_work_id: string;
  title: string;
  authors: string[];
  cover_url: string | null;
  page_count: number | null;
  publication_year: number | null;
  finished_date: string;
}

export interface CreateBookResponse {
  book: Book;
}

export interface UpdateBookRequest {
  finished_date: string;
}

export interface UpdateBookResponse {
  book: Book;
}

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

export interface ApiClient {
  getBooks(): Promise<GetBooksResponse>;
  createBook(request: CreateBookRequest): Promise<CreateBookResponse>;
  getBook(openLibraryWorkId: string): Promise<GetBookResponse>;
  updateBook(
    openLibraryWorkId: string,
    request: UpdateBookRequest,
  ): Promise<UpdateBookResponse>;
  deleteBook(openLibraryWorkId: string): Promise<void>;
}

export const apiClient: ApiClient = import.meta.env.PROD
  ? createHttpClient()
  : createFakeClient();
