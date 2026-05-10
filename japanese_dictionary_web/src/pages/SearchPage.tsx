import { useEffect, useRef, useState } from 'react';
import {
  Alert,
  Container,
  Divider,
  Loader,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';
import { apiClient } from '../api/client';
import type { SearchResult } from '../api/client';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { ResultEntry } from '../components/ResultEntry';

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
  const [hasSearched, setHasSearched] = useState(false);
  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const requestId = useRef(0);

  const runSearch = async (value: string) => {
    const trimmed = value.trim();
    const myRequest = ++requestId.current;
    if (trimmed.length === 0) {
      setResults([]);
      setLoading(false);
      setError(null);
      setHasSearched(false);
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
      setHasSearched(true);
    } catch (e) {
      if (myRequest !== requestId.current) {
        return;
      }
      const message = e instanceof Error ? e.message : 'Search failed';
      setError(message);
      setResults([]);
      setHasSearched(true);
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

  const handleInternalNavigate = (newQuery: string) => {
    if (debounceTimer.current !== null) {
      clearTimeout(debounceTimer.current);
    }
    setQuery(newQuery);
    syncUrl(newQuery);
    runSearch(newQuery);
  };

  const trimmedQuery = query.trim();
  const showEmptyHint = trimmedQuery.length === 0 && !loading;
  const showNoResults =
    !loading &&
    !error &&
    hasSearched &&
    results.length === 0 &&
    trimmedQuery.length > 0;

  return (
    <AppShellLayout>
      <Container size="md" py={{ base: 'md', sm: 'xl' }}>
        <Stack>
          <TextInput
            aria-label="Search"
            placeholder="Type a word in romaji, kana or kanji"
            value={query}
            onChange={(e) => handleChange(e.currentTarget.value)}
            size="md"
            autoFocus
          />
          {error && (
            <Alert color="red" role="alert">
              {error}
            </Alert>
          )}
          {loading && (
            <Stack align="center" py="md">
              <Loader size="sm" />
            </Stack>
          )}
          {!loading && !error && showEmptyHint && (
            <Text c="dimmed" ta="center" py="md">
              Try{' '}
              <Text span ff="monospace">
                shi
              </Text>
              ,{' '}
              <Text span ff="monospace" lang="ja">
                しん
              </Text>
              , or{' '}
              <Text span ff="monospace" lang="ja">
                新
              </Text>
            </Text>
          )}
          {showNoResults && (
            <Text c="dimmed" ta="center" py="md">
              No matches
            </Text>
          )}
          {!loading && !error && results.length > 0 && (
            <Stack gap="lg">
              {results.map((result, index) => (
                <div key={result.sequence}>
                  <ResultEntry
                    result={result}
                    onInternalNavigate={handleInternalNavigate}
                  />
                  {index < results.length - 1 && <Divider mt="lg" />}
                </div>
              ))}
            </Stack>
          )}
        </Stack>
      </Container>
    </AppShellLayout>
  );
}
