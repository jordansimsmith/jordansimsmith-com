import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { Notifications, notifications } from '@mantine/notifications';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { AddBookPage } from './AddBookPage';
import * as openLibraryModule from '../api/open-library-client';
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
      <Notifications />
      <MemoryRouter initialEntries={['/books/add']}>
        <Routes>
          <Route path="/books" element={<div>Books page</div>} />
          <Route path="/books/add" element={<AddBookPage />} />
        </Routes>
      </MemoryRouter>
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
});
