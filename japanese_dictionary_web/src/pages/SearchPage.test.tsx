import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import {
  describe,
  it,
  expect,
  beforeEach,
  afterEach,
  vi,
  type MockInstance,
} from 'vitest';
import { SearchPage } from './SearchPage';
import * as clientModule from '../api/client';
import type { SearchResult } from '../api/client';

function renderSearchPage() {
  return render(
    <MantineProvider>
      <MemoryRouter initialEntries={['/search']}>
        <Routes>
          <Route path="/search" element={<SearchPage />} />
          <Route path="/" element={<div>Login page</div>} />
        </Routes>
      </MemoryRouter>
    </MantineProvider>,
  );
}

function setUrlQuery(q: string) {
  const url = new URL('http://localhost/search');
  if (q) {
    url.searchParams.set('q', q);
  }
  window.history.replaceState({}, '', url.toString());
}

function makeResult(overrides: Partial<SearchResult>): SearchResult {
  return {
    sequence: 1,
    expression: '新',
    reading: 'しん',
    reading_romaji: 'shin',
    frequency_rank: 1,
    pitch: 0,
    glossary_raw: { tag: 'div', content: 'placeholder' },
    ...overrides,
  };
}

describe('SearchPage', () => {
  let searchSpy: MockInstance;

  beforeEach(() => {
    setUrlQuery('');
    localStorage.clear();
    searchSpy = vi
      .spyOn(clientModule.apiClient, 'search')
      .mockResolvedValue({ results: [] });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('debounces the search call by 250 ms', async () => {
    const user = userEvent.setup();

    renderSearchPage();
    await user.type(screen.getByLabelText('Search'), 'shi');

    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(searchSpy).not.toHaveBeenCalled();

    await waitFor(
      () => {
        expect(searchSpy).toHaveBeenCalledWith('shi');
      },
      { timeout: 1000 },
    );
    expect(searchSpy).toHaveBeenCalledTimes(1);
  });

  it('cancels the debounced call when input changes within the window', async () => {
    const user = userEvent.setup();

    renderSearchPage();
    const input = screen.getByLabelText('Search');
    await user.type(input, 'sh');
    await new Promise((resolve) => setTimeout(resolve, 100));
    await user.type(input, 'i');

    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(searchSpy).not.toHaveBeenCalled();

    await waitFor(
      () => {
        expect(searchSpy).toHaveBeenCalledWith('shi');
      },
      { timeout: 1000 },
    );
    expect(searchSpy).toHaveBeenCalledTimes(1);
  });

  it('mirrors the input to the URL on every change', async () => {
    const user = userEvent.setup();

    renderSearchPage();
    await user.type(screen.getByLabelText('Search'), 'a');
    expect(new URL(window.location.href).searchParams.get('q')).toBe('a');

    await user.type(screen.getByLabelText('Search'), 'b');
    expect(new URL(window.location.href).searchParams.get('q')).toBe('ab');
  });

  it('does not call the api for an empty query', async () => {
    const user = userEvent.setup();

    renderSearchPage();
    await user.type(screen.getByLabelText('Search'), 'a');
    await user.clear(screen.getByLabelText('Search'));

    await new Promise((resolve) => setTimeout(resolve, 300));

    expect(searchSpy).not.toHaveBeenCalledWith('');
    expect(new URL(window.location.href).searchParams.has('q')).toBe(false);
  });

  it('pre-populates input from ?q= and fires immediate search on mount', async () => {
    setUrlQuery('test');

    renderSearchPage();

    expect((screen.getByLabelText('Search') as HTMLInputElement).value).toBe(
      'test',
    );
    await waitFor(() => {
      expect(searchSpy).toHaveBeenCalledWith('test');
    });
  });

  it('shows the empty-state hint when no query is entered', () => {
    renderSearchPage();
    expect(
      screen.getByText(/type a word in romaji, kana or kanji/i),
    ).toBeDefined();
  });

  it('shows a loading indicator while the search is in flight', async () => {
    let resolveSearch: (value: { results: SearchResult[] }) => void = () => {};
    searchSpy.mockReturnValue(
      new Promise<{ results: SearchResult[] }>((resolve) => {
        resolveSearch = resolve;
      }),
    );
    setUrlQuery('shi');
    renderSearchPage();

    await waitFor(() => {
      expect(document.querySelector('.mantine-Loader-root')).not.toBeNull();
    });

    resolveSearch({ results: [] });
  });

  it('shows the no-matches hint when the search returns nothing', async () => {
    setUrlQuery('zzz');
    renderSearchPage();

    await waitFor(() => {
      expect(screen.getByText(/no matches/i)).toBeDefined();
    });
  });

  it('shows an error message when the search throws', async () => {
    searchSpy.mockRejectedValue(new Error('boom'));
    setUrlQuery('shi');
    renderSearchPage();

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeDefined();
    });
    expect(screen.getByRole('alert').textContent).toContain('boom');
  });

  it('renders ResultEntry components for each search result', async () => {
    searchSpy.mockResolvedValue({
      results: [
        makeResult({ sequence: 100, expression: '新聞', reading: 'しんぶん' }),
        makeResult({ sequence: 101, expression: '新年', reading: 'しんねん' }),
      ],
    });
    setUrlQuery('しん');
    renderSearchPage();

    await waitFor(() => {
      expect(screen.getByText('新聞')).toBeDefined();
    });
    expect(screen.getByText('新年')).toBeDefined();
  });

  it('clears the session and navigates home when logout is clicked', async () => {
    localStorage.setItem(
      'japanese_dictionary_auth',
      JSON.stringify({ username: 'alice', token: 'abc' }),
    );
    renderSearchPage();

    const user = userEvent.setup();
    await user.click(screen.getByLabelText(/log out/i));

    await waitFor(() => {
      expect(screen.getByText('Login page')).toBeDefined();
    });
    expect(localStorage.getItem('japanese_dictionary_auth')).toBeNull();
  });

  it('runs an immediate search when an internal link triggers navigation', async () => {
    const internalNode = {
      tag: 'a',
      href: '?query=寺',
      content: '寺',
    };
    searchSpy.mockResolvedValueOnce({
      results: [
        makeResult({
          sequence: 200,
          expression: '神社',
          reading: 'じんじゃ',
          glossary_raw: internalNode,
        }),
      ],
    });
    setUrlQuery('じん');
    renderSearchPage();

    const link = await screen.findByRole('link', { name: '寺' });
    searchSpy.mockResolvedValue({
      results: [
        makeResult({ sequence: 300, expression: '寺', reading: 'てら' }),
      ],
    });

    const user = userEvent.setup();
    await user.click(link);

    await waitFor(() => {
      expect(searchSpy).toHaveBeenCalledWith('寺');
    });
    expect(new URL(window.location.href).searchParams.get('q')).toBe('寺');
    expect(await screen.findByText('寺')).toBeDefined();
  });
});
