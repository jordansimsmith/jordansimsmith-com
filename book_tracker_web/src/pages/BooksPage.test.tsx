import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { DatesProvider } from '@mantine/dates';
import { Notifications, notifications } from '@mantine/notifications';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { BooksPage } from './BooksPage';
import * as clientModule from '../api/client';
import type { Book } from '../api/client';
import { LibraryStatsProvider } from '../layouts/library-stats';

const sampleBooks: Book[] = [
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
    cover_url: null,
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
    finished_date: '2026-03-04',
    created_at: 1712275200,
    updated_at: 1712275200,
  },
];

function renderBooksPage() {
  localStorage.setItem(
    'book_tracker_auth',
    JSON.stringify({ username: 'alice', token: btoa('alice:secret') }),
  );

  return render(
    <MantineProvider>
      <DatesProvider settings={{}}>
        <Notifications />
        <LibraryStatsProvider>
          <MemoryRouter initialEntries={['/books']}>
            <Routes>
              <Route path="/" element={<div>Login page</div>} />
              <Route path="/books" element={<BooksPage />} />
              <Route path="/books/add" element={<div>Add book page</div>} />
            </Routes>
          </MemoryRouter>
        </LibraryStatsProvider>
      </DatesProvider>
    </MantineProvider>,
  );
}

describe('BooksPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
    notifications.clean();
  });

  afterEach(() => {
    cleanup();
    notifications.clean();
  });

  it('renders books grouped by month in descending order', async () => {
    vi.spyOn(clientModule.apiClient, 'getBooks').mockResolvedValue({
      books: sampleBooks,
      rolling_12_month_count: 7,
    });

    renderBooksPage();

    await waitFor(() => {
      expect(screen.getByText('Apr 2026')).toBeDefined();
    });

    const monthHeadings = screen
      .getAllByRole('heading', { level: 3 })
      .map((heading) => heading.textContent ?? '')
      .filter((text) => /^[A-Z][a-z]+ \d{4}$/.test(text));
    expect(monthHeadings).toEqual(['Apr 2026', 'Mar 2026']);

    expect(screen.getByText('The Lord of the Rings')).toBeDefined();
    expect(screen.getAllByText('Pride and Prejudice').length).toBeGreaterThan(
      0,
    );
    expect(screen.getByText('Beloved')).toBeDefined();
    expect(screen.getByText('Jane Austen')).toBeDefined();
  });

  it('renders the rolling 12-month count from the response', async () => {
    vi.spyOn(clientModule.apiClient, 'getBooks').mockResolvedValue({
      books: sampleBooks,
      rolling_12_month_count: 17,
    });

    renderBooksPage();

    await waitFor(() => {
      expect(screen.getByText(/17 in last 12 months/i)).toBeDefined();
    });
  });

  it('shows the empty state when no books are returned', async () => {
    vi.spyOn(clientModule.apiClient, 'getBooks').mockResolvedValue({
      books: [],
      rolling_12_month_count: 0,
    });

    renderBooksPage();

    await waitFor(() => {
      expect(screen.getByText(/no finished books yet/i)).toBeDefined();
    });
    expect(screen.getByText(/0 in last 12 months/i)).toBeDefined();
  });

  it('surfaces an error when loading fails', async () => {
    vi.spyOn(clientModule.apiClient, 'getBooks').mockRejectedValue(
      new clientModule.ApiError(500, 'Server error'),
    );

    renderBooksPage();

    await waitFor(() => {
      expect(screen.getAllByText('Server error').length).toBeGreaterThan(0);
    });
  });

  it('opens the edit modal when a book card is clicked', async () => {
    vi.spyOn(clientModule.apiClient, 'getBooks').mockResolvedValue({
      books: sampleBooks,
      rolling_12_month_count: 3,
    });

    const user = userEvent.setup();
    renderBooksPage();

    const card = await screen.findByRole('button', {
      name: /edit the lord of the rings/i,
    });
    await user.click(card);

    expect(
      await screen.findByText(/edit the lord of the rings/i, undefined, {
        timeout: 2000,
      }),
    ).toBeDefined();
    expect(screen.getByText(/^finished date$/i)).toBeDefined();
  });

  it('updates the book and refreshes the timeline when the edit is saved', async () => {
    const updatedBook: Book = {
      ...sampleBooks[0],
      finished_date: '2026-02-10',
      updated_at: 1714900000,
    };

    const refreshedBooks = [sampleBooks[1], sampleBooks[2], { ...updatedBook }];

    vi.spyOn(clientModule.apiClient, 'getBooks')
      .mockResolvedValueOnce({
        books: sampleBooks,
        rolling_12_month_count: 7,
      })
      .mockResolvedValue({
        books: refreshedBooks,
        rolling_12_month_count: 6,
      });
    const updateSpy = vi
      .spyOn(clientModule.apiClient, 'updateBook')
      .mockResolvedValue({ book: updatedBook });

    const user = userEvent.setup();
    renderBooksPage();

    const card = await screen.findByRole('button', {
      name: /edit the lord of the rings/i,
    });
    await user.click(card);

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toBeDefined();

    const saveButton = screen.getByRole('button', { name: /^save$/i });
    await user.click(saveButton);

    await waitFor(() => expect(updateSpy).toHaveBeenCalledTimes(1));
    expect(updateSpy).toHaveBeenCalledWith('OL27448W', {
      finished_date: '2026-04-28',
    });

    await waitFor(() => {
      expect(screen.getByText('Feb 2026')).toBeDefined();
    });

    expect(screen.getByText(/6 in last 12 months/i)).toBeDefined();
  });
});
