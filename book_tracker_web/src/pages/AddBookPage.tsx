import { useEffect, useMemo, useState } from 'react';
import {
  ActionIcon,
  Button,
  Container,
  Group,
  Image,
  Loader,
  Paper,
  Stack,
  Text,
  TextInput,
  Title,
  Box,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import { IconArrowLeft, IconSearch } from '@tabler/icons-react';
import { useNavigate } from 'react-router-dom';
import { openLibraryClient } from '../api/open-library-client';
import type { OpenLibrarySearchResult } from '../api/open-library-client';

const SEARCH_DEBOUNCE_MS = 300;

export function buildCoverUrl(coverId: number | null): string | null {
  if (coverId == null) {
    return null;
  }
  return `https://covers.openlibrary.org/b/id/${coverId}-L.jpg`;
}

interface ResultRowProps {
  result: OpenLibrarySearchResult;
  selected: boolean;
  onSelect: (result: OpenLibrarySearchResult) => void;
}

function ResultRow({ result, selected, onSelect }: ResultRowProps) {
  const coverUrl = buildCoverUrl(result.cover_id);
  const authorsLabel =
    result.author_names.length > 0
      ? result.author_names.join(', ')
      : 'Unknown author';
  const yearLabel = result.first_publish_year
    ? ` · ${result.first_publish_year}`
    : '';

  return (
    <Paper
      p="sm"
      withBorder
      radius="md"
      style={{
        borderColor: selected
          ? 'var(--mantine-color-violet-filled)'
          : undefined,
        background: selected
          ? 'var(--mantine-color-violet-light)'
          : undefined,
      }}
    >
      <Group wrap="nowrap" align="flex-start">
        <Box style={{ width: 60, flexShrink: 0 }}>
          {coverUrl ? (
            <Image
              src={coverUrl}
              alt={`Cover of ${result.title}`}
              w={60}
              h={90}
              fit="cover"
              radius="sm"
            />
          ) : (
            <Box
              w={60}
              h={90}
              style={{
                background: 'var(--mantine-color-violet-light)',
                borderRadius: 'var(--mantine-radius-sm)',
              }}
            />
          )}
        </Box>
        <Stack gap={4} style={{ flex: 1, minWidth: 0 }}>
          <Text fw={600} lineClamp={2}>
            {result.title}
          </Text>
          <Text size="sm" c="dimmed" lineClamp={1}>
            {authorsLabel}
            {yearLabel}
          </Text>
        </Stack>
        <Button
          size="xs"
          variant={selected ? 'filled' : 'light'}
          onClick={() => onSelect(result)}
        >
          {selected ? 'Selected' : 'Select'}
        </Button>
      </Group>
    </Paper>
  );
}

export function AddBookPage() {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [debouncedQuery] = useDebouncedValue(query, SEARCH_DEBOUNCE_MS);
  const [results, setResults] = useState<OpenLibrarySearchResult[]>([]);
  const [searching, setSearching] = useState(false);
  const [selected, setSelected] = useState<OpenLibrarySearchResult | null>(
    null,
  );

  const trimmedQuery = useMemo(() => debouncedQuery.trim(), [debouncedQuery]);

  useEffect(() => {
    if (!trimmedQuery) {
      setResults([]);
      setSearching(false);
      return;
    }

    let cancelled = false;
    setSearching(true);
    openLibraryClient
      .search(trimmedQuery)
      .then((response) => {
        if (cancelled) {
          return;
        }
        setResults(response.results);
      })
      .catch((e) => {
        if (cancelled) {
          return;
        }
        const message = e instanceof Error ? e.message : 'Search failed';
        notifications.show({ title: 'Search failed', message, color: 'red' });
        setResults([]);
      })
      .finally(() => {
        if (!cancelled) {
          setSearching(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [trimmedQuery]);

  const handleSelect = (result: OpenLibrarySearchResult) => {
    setSelected(result);
  };

  return (
    <Container size="md" py="xl">
      <Stack gap="lg">
        <Group>
          <ActionIcon
            variant="subtle"
            onClick={() => navigate('/books')}
            aria-label="Back to books"
          >
            <IconArrowLeft size={20} />
          </ActionIcon>
          <Title order={1}>Add a book</Title>
        </Group>

        <TextInput
          label="Search Open Library"
          placeholder="Title or author"
          leftSection={<IconSearch size={16} />}
          rightSection={searching ? <Loader size="xs" /> : null}
          value={query}
          onChange={(event) => setQuery(event.currentTarget.value)}
          autoFocus
        />

        {!trimmedQuery && (
          <Text c="dimmed" size="sm">
            Type a book title or author to search Open Library.
          </Text>
        )}

        {trimmedQuery && !searching && results.length === 0 && (
          <Text c="dimmed" size="sm">
            No results for &ldquo;{trimmedQuery}&rdquo;.
          </Text>
        )}

        {results.length > 0 && (
          <Stack gap="sm">
            {results.map((result) => (
              <ResultRow
                key={result.open_library_work_id}
                result={result}
                selected={
                  selected?.open_library_work_id === result.open_library_work_id
                }
                onSelect={handleSelect}
              />
            ))}
          </Stack>
        )}
      </Stack>
    </Container>
  );
}
