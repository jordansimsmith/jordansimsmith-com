import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { DatesProvider } from '@mantine/dates';
import { Notifications, notifications } from '@mantine/notifications';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { AddBookPage, buildCoverUrl } from './AddBookPage';
import * as openLibraryModule from '../api/open-library-client';
import * as clientModule from '../api/client';
import { ApiError } from '../api/client';
import type { OpenLibrarySearchResult } from '../api/open-library-client';

const mockResults: OpenLibrarySearchResult[] = [
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
];

function renderAddBookPage() {
  return render(
    <MantineProvider>
      <DatesProvider settings={{}}>
        <Notifications />
        <MemoryRouter initialEntries={['/books/add']}>
          <Routes>
            <Route path="/books" element={<div>Books page</div>} />
            <Route path="/books/add" element={<AddBookPage />} />
          </Routes>
        </MemoryRouter>
      </DatesProvider>
    </MantineProvider>,
  );
}

describe('AddBookPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    notifications.clean();
  });

  afterEach(() => {
    cleanup();
    notifications.clean();
  });

  it('renders the search prompt with no query entered', () => {
    renderAddBookPage();

    expect(screen.getByLabelText(/search open library/i)).toBeDefined();
    expect(screen.getByText(/type a book title or author/i)).toBeDefined();
  });

  it('does not query the open library client when the input is empty', async () => {
    const searchSpy = vi
      .spyOn(openLibraryModule.openLibraryClient, 'search')
      .mockResolvedValue({ results: [] });

    renderAddBookPage();

    await new Promise((resolve) => setTimeout(resolve, 400));
    expect(searchSpy).not.toHaveBeenCalled();
  });

  it('renders search results returned by the open library client', async () => {
    const searchSpy = vi
      .spyOn(openLibraryModule.openLibraryClient, 'search')
      .mockResolvedValue({ results: mockResults });

    const user = userEvent.setup();
    renderAddBookPage();

    await user.type(screen.getByLabelText(/search open library/i), 'rothfuss');

    await waitFor(() => expect(searchSpy).toHaveBeenCalledWith('rothfuss'));

    expect(await screen.findByText('The Name of the Wind')).toBeDefined();
    expect(screen.getByText("The Wise Man's Fear")).toBeDefined();
    expect(screen.getAllByText(/patrick rothfuss/i).length).toBeGreaterThan(0);
  });

  it('marks a result as selected after clicking Select', async () => {
    vi.spyOn(openLibraryModule.openLibraryClient, 'search').mockResolvedValue({
      results: mockResults,
    });

    const user = userEvent.setup();
    renderAddBookPage();

    await user.type(screen.getByLabelText(/search open library/i), 'rothfuss');

    expect(await screen.findByText('The Name of the Wind')).toBeDefined();

    const selectButtons = screen.getAllByRole('button', { name: /^select$/i });
    await user.click(selectButtons[0]);

    expect(screen.getByRole('button', { name: /selected/i })).toBeDefined();
  });

  it('shows the no-results message when the search returns nothing', async () => {
    vi.spyOn(openLibraryModule.openLibraryClient, 'search').mockResolvedValue({
      results: [],
    });

    const user = userEvent.setup();
    renderAddBookPage();

    await user.type(
      screen.getByLabelText(/search open library/i),
      'nonexistent',
    );

    expect(await screen.findByText(/no results for/i)).toBeDefined();
  });

  it('disables Add to library until a result is selected', async () => {
    vi.spyOn(openLibraryModule.openLibraryClient, 'search').mockResolvedValue({
      results: mockResults,
    });

    const user = userEvent.setup();
    renderAddBookPage();

    const submit = screen.getByRole('button', { name: /add to library/i });
    expect(submit.hasAttribute('disabled')).toBe(true);

    await user.type(screen.getByLabelText(/search open library/i), 'rothfuss');
    expect(await screen.findByText('The Name of the Wind')).toBeDefined();

    const selectButtons = screen.getAllByRole('button', { name: /^select$/i });
    await user.click(selectButtons[0]);

    expect(submit.hasAttribute('disabled')).toBe(false);
  });

  it('creates the book, shows a success notification, and navigates to /books', async () => {
    vi.spyOn(openLibraryModule.openLibraryClient, 'search').mockResolvedValue({
      results: mockResults,
    });
    const createSpy = vi
      .spyOn(clientModule.apiClient, 'createBook')
      .mockResolvedValue({
        book: {
          open_library_work_id: 'OL14854528W',
          title: 'The Name of the Wind',
          authors: ['Patrick Rothfuss'],
          cover_url: buildCoverUrl(14627509),
          page_count: 662,
          publication_year: 2007,
          finished_date: '2026-05-04',
          created_at: 1714809600,
          updated_at: 1714809600,
        },
      });

    const user = userEvent.setup();
    renderAddBookPage();

    await user.type(screen.getByLabelText(/search open library/i), 'rothfuss');
    expect(await screen.findByText('The Name of the Wind')).toBeDefined();

    const selectButtons = screen.getAllByRole('button', { name: /^select$/i });
    await user.click(selectButtons[0]);
    await user.click(screen.getByRole('button', { name: /add to library/i }));

    await waitFor(() => expect(createSpy).toHaveBeenCalledTimes(1));
    expect(createSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        open_library_work_id: 'OL14854528W',
        title: 'The Name of the Wind',
        authors: ['Patrick Rothfuss'],
        cover_url: 'https://covers.openlibrary.org/b/id/14627509-L.jpg',
        page_count: 662,
        publication_year: 2007,
      }),
    );

    await waitFor(() => {
      expect(screen.getByText('Books page')).toBeDefined();
    });
    expect(screen.getByText(/added to your timeline/i)).toBeDefined();
  });

  it('surfaces a 409 conflict notification and stays on the page', async () => {
    vi.spyOn(openLibraryModule.openLibraryClient, 'search').mockResolvedValue({
      results: mockResults,
    });
    vi.spyOn(clientModule.apiClient, 'createBook').mockRejectedValue(
      new ApiError(409, 'already added on 2026-04-12'),
    );

    const user = userEvent.setup();
    renderAddBookPage();

    await user.type(screen.getByLabelText(/search open library/i), 'rothfuss');
    expect(await screen.findByText('The Name of the Wind')).toBeDefined();

    const selectButtons = screen.getAllByRole('button', { name: /^select$/i });
    await user.click(selectButtons[0]);
    await user.click(screen.getByRole('button', { name: /add to library/i }));

    await waitFor(() => {
      expect(screen.getByText(/already added on 2026-04-12/i)).toBeDefined();
    });
    expect(screen.queryByText('Books page')).toBeNull();
  });
});

describe('buildCoverUrl', () => {
  it('returns null when cover_id is missing', () => {
    expect(buildCoverUrl(null)).toBeNull();
  });

  it('builds the canonical Open Library covers URL', () => {
    expect(buildCoverUrl(14627509)).toBe(
      'https://covers.openlibrary.org/b/id/14627509-L.jpg',
    );
  });
});
