import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { MemoryRouter } from 'react-router-dom';
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

function renderSearchPage() {
  return render(
    <MantineProvider>
      <MemoryRouter>
        <SearchPage />
      </MemoryRouter>
    </MantineProvider>,
  );
}

function setUrlQuery(q: string) {
  const url = new URL('http://localhost/');
  if (q) {
    url.searchParams.set('q', q);
  }
  window.history.replaceState({}, '', url.toString());
}

describe('SearchPage', () => {
  let searchSpy: MockInstance;

  beforeEach(() => {
    setUrlQuery('');
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

  it('renders results returned from the api', async () => {
    searchSpy.mockResolvedValue({
      results: [
        {
          sequence: 12345,
          expression: '新',
          reading: 'しん',
          reading_romaji: 'shin',
          frequency_rank: 1,
          pitch: 0,
          glossary_raw: { tag: 'div', content: 'new' },
        },
      ],
    });
    setUrlQuery('shin');

    renderSearchPage();

    await waitFor(() => {
      expect(screen.getByText(/12345/)).toBeDefined();
    });
  });
});
