import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createHttpClient } from './http-client';
import { ApiError, type Book, type CreateBookRequest } from './client';
import { setSession, clearSession } from '../auth/session';

const BASE_URL = 'https://api.book-tracker.jordansimsmith.com';

const sampleBook: Book = {
  open_library_work_id: 'OL27448W',
  title: 'The Lord of the Rings',
  authors: ['J.R.R. Tolkien'],
  cover_url: 'https://covers.openlibrary.org/b/id/14625765-L.jpg',
  page_count: 1193,
  publication_year: 1954,
  finished_date: '2026-04-15',
  created_at: 1714809600,
  updated_at: 1714809600,
};

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function emptyResponse(status: number): Response {
  return new Response(null, { status });
}

describe('createHttpClient', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    setSession('alice', 'password');
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    clearSession();
  });

  describe('getBooks', () => {
    it('returns the parsed body on success', async () => {
      fetchMock.mockResolvedValueOnce(
        jsonResponse(200, {
          books: [sampleBook],
          rolling_12_month_count: 1,
        }),
      );

      const client = createHttpClient();
      const result = await client.getBooks();

      expect(result).toEqual({
        books: [sampleBook],
        rolling_12_month_count: 1,
      });
      expect(fetchMock).toHaveBeenCalledWith(`${BASE_URL}/books`, {
        headers: { Authorization: `Basic ${btoa('alice:password')}` },
      });
    });

    it('throws ApiError with the backend message on failure', async () => {
      fetchMock.mockResolvedValueOnce(
        jsonResponse(401, { message: 'invalid credentials' }),
      );

      const client = createHttpClient();

      await expect(client.getBooks()).rejects.toMatchObject({
        name: 'ApiError',
        status: 401,
        message: 'invalid credentials',
      });
    });
  });

  describe('getBook', () => {
    it('returns the parsed body on success', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse(200, { book: sampleBook }));

      const client = createHttpClient();
      const result = await client.getBook('OL27448W');

      expect(result).toEqual({ book: sampleBook });
      expect(fetchMock).toHaveBeenCalledWith(`${BASE_URL}/books/OL27448W`, {
        headers: { Authorization: `Basic ${btoa('alice:password')}` },
      });
    });

    it('throws ApiError with status 404 when not found', async () => {
      fetchMock.mockResolvedValueOnce(
        jsonResponse(404, { message: 'Not Found' }),
      );

      const client = createHttpClient();

      await expect(client.getBook('OL999W')).rejects.toMatchObject({
        name: 'ApiError',
        status: 404,
        message: 'Not Found',
      });
    });
  });

  describe('createBook', () => {
    const request: CreateBookRequest = {
      open_library_work_id: 'OL27448W',
      title: 'The Lord of the Rings',
      authors: ['J.R.R. Tolkien'],
      cover_url: 'https://covers.openlibrary.org/b/id/14625765-L.jpg',
      page_count: 1193,
      publication_year: 1954,
      finished_date: '2026-04-15',
    };

    it('posts the request and returns the created book', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse(201, { book: sampleBook }));

      const client = createHttpClient();
      const result = await client.createBook(request);

      expect(result).toEqual({ book: sampleBook });
      expect(fetchMock).toHaveBeenCalledWith(`${BASE_URL}/books`, {
        method: 'POST',
        headers: {
          Authorization: `Basic ${btoa('alice:password')}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(request),
      });
    });

    it('throws ApiError with status 409 when the book is already added', async () => {
      fetchMock.mockResolvedValueOnce(
        jsonResponse(409, { message: 'already added on 2026-04-12' }),
      );

      const client = createHttpClient();

      const error = await client.createBook(request).catch((e: unknown) => e);
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).status).toBe(409);
      expect((error as ApiError).message).toBe('already added on 2026-04-12');
    });
  });

  describe('updateBook', () => {
    it('puts the request and returns the updated book', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse(200, { book: sampleBook }));

      const client = createHttpClient();
      const result = await client.updateBook('OL27448W', {
        finished_date: '2026-04-15',
      });

      expect(result).toEqual({ book: sampleBook });
      expect(fetchMock).toHaveBeenCalledWith(`${BASE_URL}/books/OL27448W`, {
        method: 'PUT',
        headers: {
          Authorization: `Basic ${btoa('alice:password')}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ finished_date: '2026-04-15' }),
      });
    });

    it('throws ApiError with status 400 on validation failure', async () => {
      fetchMock.mockResolvedValueOnce(
        jsonResponse(400, { message: 'finished_date must be YYYY-MM-DD' }),
      );

      const client = createHttpClient();

      await expect(
        client.updateBook('OL27448W', { finished_date: 'invalid' }),
      ).rejects.toMatchObject({
        name: 'ApiError',
        status: 400,
        message: 'finished_date must be YYYY-MM-DD',
      });
    });
  });

  describe('deleteBook', () => {
    it('issues a delete request and resolves on success', async () => {
      fetchMock.mockResolvedValueOnce(emptyResponse(204));

      const client = createHttpClient();
      await expect(client.deleteBook('OL27448W')).resolves.toBeUndefined();

      expect(fetchMock).toHaveBeenCalledWith(`${BASE_URL}/books/OL27448W`, {
        method: 'DELETE',
        headers: { Authorization: `Basic ${btoa('alice:password')}` },
      });
    });

    it('throws ApiError with status 404 when the book is missing', async () => {
      fetchMock.mockResolvedValueOnce(
        jsonResponse(404, { message: 'Not Found' }),
      );

      const client = createHttpClient();

      await expect(client.deleteBook('OL999W')).rejects.toMatchObject({
        name: 'ApiError',
        status: 404,
        message: 'Not Found',
      });
    });
  });

  it('throws a 401 ApiError when no session is present', async () => {
    clearSession();
    const client = createHttpClient();

    await expect(client.getBooks()).rejects.toMatchObject({
      name: 'ApiError',
      status: 401,
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('falls back to the status text when the error body is not JSON', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response('something went wrong', {
        status: 500,
        statusText: 'Internal Server Error',
      }),
    );

    const client = createHttpClient();

    await expect(client.getBooks()).rejects.toMatchObject({
      name: 'ApiError',
      status: 500,
      message: 'Internal Server Error',
    });
  });
});
