import type { ApiClient } from './client';

export function createHttpClient(): ApiClient {
  const notImplemented = (method: string) => () => {
    throw new Error(`${method} is not implemented`);
  };

  return {
    getBooks: notImplemented('getBooks'),
    getBook: notImplemented('getBook'),
    createBook: notImplemented('createBook'),
    updateBook: notImplemented('updateBook'),
    deleteBook: notImplemented('deleteBook'),
  };
}
