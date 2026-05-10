import { useEffect, useRef, useState } from 'react';
import { Container, Stack, TextInput, Title } from '@mantine/core';
import { apiClient } from '../api/client';
import type { SearchResult } from '../api/client';

const DEBOUNCE_MS = 250;

function syncUrl(query: string) {
  const url = new URL(window.location.href);
  if (query) {
    url.searchParams.set('q', query);
  } else {
    url.searchParams.delete('q');
  }
  window.history.replaceState({}, '', url.toString());
}

export function SearchPage() {
  const initialQuery =
    typeof window !== 'undefined'
      ? (new URLSearchParams(window.location.search).get('q') ?? '')
      : '';

  const [query, setQuery] = useState(initialQuery);
  const [results, setResults] = useState<SearchResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const requestId = useRef(0);

  const runSearch = async (value: string) => {
    const trimmed = value.trim();
    const myRequest = ++requestId.current;
    if (trimmed.length === 0) {
      setResults([]);
      setLoading(false);
      setError(null);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const response = await apiClient.search(trimmed);
      if (myRequest !== requestId.current) {
        return;
      }
      setResults(response.results);
    } catch (e) {
      if (myRequest !== requestId.current) {
        return;
      }
      const message = e instanceof Error ? e.message : 'Search failed';
      setError(message);
      setResults([]);
    } finally {
      if (myRequest === requestId.current) {
        setLoading(false);
      }
    }
  };

  useEffect(() => {
    if (initialQuery) {
      runSearch(initialQuery);
    }
    return () => {
      if (debounceTimer.current !== null) {
        clearTimeout(debounceTimer.current);
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleChange = (value: string) => {
    setQuery(value);
    syncUrl(value);
    if (debounceTimer.current !== null) {
      clearTimeout(debounceTimer.current);
    }
    debounceTimer.current = setTimeout(() => {
      runSearch(value);
    }, DEBOUNCE_MS);
  };

  return (
    <Container size="md" py="xl">
      <Stack>
        <Title order={1}>Japanese dictionary</Title>
        <TextInput
          aria-label="Search"
          placeholder="Type a word in romaji, kana or kanji"
          value={query}
          onChange={(e) => handleChange(e.currentTarget.value)}
          autoFocus
        />
        {error && <div role="alert">{error}</div>}
        {loading && <div>Loading…</div>}
        {results.map((result) => (
          <pre key={result.sequence}>{JSON.stringify(result, null, 2)}</pre>
        ))}
      </Stack>
    </Container>
  );
}
