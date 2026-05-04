import { getSession } from '../auth/session';
import { ApiError } from './client';
import type {
  ApiClient,
  CreateBookRequest,
  CreateBookResponse,
  GetBookResponse,
  GetBooksResponse,
  UpdateBookRequest,
  UpdateBookResponse,
} from './client';

const BASE_URL =
  import.meta.env.VITE_API_BASE_URL ||
  'https://api.book-tracker.jordansimsmith.com';

function authHeader(): { Authorization: string } {
  const session = getSession();
  if (!session) {
    throw new ApiError(401, 'Not authenticated');
  }
  return { Authorization: `Basic ${session.token}` };
}

async function ensureOk(response: Response): Promise<void> {
  if (response.ok) {
    return;
  }
  let message = response.statusText || `Request failed with ${response.status}`;
  try {
    const body = await response.json();
    if (body && typeof body.message === 'string' && body.message.length > 0) {
      message = body.message;
    }
  } catch {
    // body was not JSON; fall back to status text
  }
  throw new ApiError(response.status, message);
}

export function createHttpClient(): ApiClient {
  return {
    async getBooks(): Promise<GetBooksResponse> {
      const response = await fetch(`${BASE_URL}/books`, {
        headers: { ...authHeader() },
      });
      await ensureOk(response);
      return response.json();
    },

    async getBook(openLibraryWorkId: string): Promise<GetBookResponse> {
      const response = await fetch(
        `${BASE_URL}/books/${encodeURIComponent(openLibraryWorkId)}`,
        {
          headers: { ...authHeader() },
        },
      );
      await ensureOk(response);
      return response.json();
    },

    async createBook(request: CreateBookRequest): Promise<CreateBookResponse> {
      const response = await fetch(`${BASE_URL}/books`, {
        method: 'POST',
        headers: {
          ...authHeader(),
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(request),
      });
      await ensureOk(response);
      return response.json();
    },

    async updateBook(
      openLibraryWorkId: string,
      request: UpdateBookRequest,
    ): Promise<UpdateBookResponse> {
      const response = await fetch(
        `${BASE_URL}/books/${encodeURIComponent(openLibraryWorkId)}`,
        {
          method: 'PUT',
          headers: {
            ...authHeader(),
            'Content-Type': 'application/json',
          },
          body: JSON.stringify(request),
        },
      );
      await ensureOk(response);
      return response.json();
    },

    async deleteBook(openLibraryWorkId: string): Promise<void> {
      const response = await fetch(
        `${BASE_URL}/books/${encodeURIComponent(openLibraryWorkId)}`,
        {
          method: 'DELETE',
          headers: { ...authHeader() },
        },
      );
      await ensureOk(response);
    },
  };
}
