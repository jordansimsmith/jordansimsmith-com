import { useEffect, useMemo, useState } from 'react';
import {
  ActionIcon,
  Box,
  Button,
  Combobox,
  Container,
  Divider,
  Group,
  Image,
  Loader,
  Paper,
  Stack,
  Text,
  TextInput,
  Title,
  useCombobox,
} from '@mantine/core';
import { DatePickerInput } from '@mantine/dates';
import { useDebouncedValue } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import { IconArrowLeft, IconSearch, IconX } from '@tabler/icons-react';
import { useNavigate } from 'react-router-dom';
import { ApiError, apiClient } from '../api/client';
import { openLibraryClient } from '../api/open-library-client';
import type { OpenLibrarySearchResult } from '../api/open-library-client';
import { AppShellLayout } from '../layouts/AppShellLayout';

const SEARCH_DEBOUNCE_MS = 300;
const FINISHED_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

export function buildCoverUrl(coverId: number | null): string | null {
  if (coverId == null) {
    return null;
  }
  return `https://covers.openlibrary.org/b/id/${coverId}-L.jpg`;
}

function todayIsoDate(): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function formatAuthorsAndYear(result: OpenLibrarySearchResult): string {
  const authors =
    result.author_names.length > 0
      ? result.author_names.join(', ')
      : 'Unknown author';
  const year = result.first_publish_year
    ? ` · ${result.first_publish_year}`
    : '';
  return `${authors}${year}`;
}

interface CoverThumbProps {
  coverId: number | null;
  title: string;
  width: number;
  height: number;
}

function CoverThumb({ coverId, title, width, height }: CoverThumbProps) {
  const url = buildCoverUrl(coverId);
  if (url) {
    return (
      <Image
        src={url}
        alt={`Cover of ${title}`}
        w={width}
        h={height}
        fit="cover"
        radius="sm"
      />
    );
  }
  return (
    <Box
      w={width}
      h={height}
      style={{
        background: 'var(--mantine-color-violet-light)',
        borderRadius: 'var(--mantine-radius-sm)',
      }}
    />
  );
}

export function AddBookPage() {
  const navigate = useNavigate();
  const combobox = useCombobox({
    onDropdownClose: () => combobox.resetSelectedOption(),
  });
  const [query, setQuery] = useState('');
  const [debouncedQuery] = useDebouncedValue(query, SEARCH_DEBOUNCE_MS);
  const [results, setResults] = useState<OpenLibrarySearchResult[]>([]);
  const [searching, setSearching] = useState(false);
  const [selected, setSelected] = useState<OpenLibrarySearchResult | null>(
    null,
  );
  const [finishedDate, setFinishedDate] = useState<string | null>(
    todayIsoDate(),
  );
  const [submitting, setSubmitting] = useState(false);

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

  const handleOptionSubmit = (value: string) => {
    const picked = results.find((r) => r.open_library_work_id === value);
    if (picked) {
      setSelected(picked);
    }
    combobox.closeDropdown();
  };

  const submitDisabled =
    !selected ||
    !finishedDate ||
    !FINISHED_DATE_PATTERN.test(finishedDate) ||
    submitting;

  const handleSubmit = async () => {
    if (!selected || !finishedDate) {
      return;
    }

    setSubmitting(true);
    try {
      await apiClient.createBook({
        open_library_work_id: selected.open_library_work_id,
        title: selected.title,
        authors: selected.author_names,
        cover_url: buildCoverUrl(selected.cover_id),
        page_count: selected.number_of_pages_median,
        publication_year: selected.first_publish_year,
        finished_date: finishedDate,
      });
      notifications.show({
        title: 'Book added',
        message: `${selected.title} added to your timeline`,
        color: 'green',
      });
      navigate('/books');
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        notifications.show({
          title: 'Already in your library',
          message: e.message,
          color: 'yellow',
        });
        return;
      }
      const message = e instanceof Error ? e.message : 'Failed to add book';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setSubmitting(false);
    }
  };

  const dropdownHasContent =
    !!trimmedQuery && (searching || results.length > 0 || results.length === 0);

  const options = results.map((result) => (
    <Combobox.Option
      value={result.open_library_work_id}
      key={result.open_library_work_id}
    >
      <Group wrap="nowrap" gap="sm" align="flex-start">
        <CoverThumb
          coverId={result.cover_id}
          title={result.title}
          width={40}
          height={60}
        />
        <Stack gap={2} style={{ flex: 1, minWidth: 0 }}>
          <Text fw={600} size="sm" lineClamp={2}>
            {result.title}
          </Text>
          <Text size="xs" c="dimmed" lineClamp={1}>
            {formatAuthorsAndYear(result)}
          </Text>
        </Stack>
      </Group>
    </Combobox.Option>
  ));

  return (
    <AppShellLayout>
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

          <Combobox store={combobox} onOptionSubmit={handleOptionSubmit}>
            <Combobox.Target>
              <TextInput
                label="Search Open Library"
                placeholder="Title or author"
                leftSection={<IconSearch size={16} />}
                rightSection={searching ? <Loader size="xs" /> : null}
                value={query}
                onChange={(event) => {
                  setQuery(event.currentTarget.value);
                  combobox.openDropdown();
                  combobox.updateSelectedOptionIndex();
                }}
                onFocus={() => {
                  if (trimmedQuery) {
                    combobox.openDropdown();
                  }
                }}
                onClick={() => {
                  if (trimmedQuery) {
                    combobox.openDropdown();
                  }
                }}
                onBlur={() => combobox.closeDropdown()}
                autoFocus
              />
            </Combobox.Target>

            <Combobox.Dropdown hidden={!dropdownHasContent}>
              <Combobox.Options
                mah={360}
                style={{ overflowY: 'auto' }}
                aria-label="Open Library search results"
              >
                {searching && results.length === 0 && (
                  <Combobox.Empty>Searching…</Combobox.Empty>
                )}
                {!searching && results.length === 0 && trimmedQuery && (
                  <Combobox.Empty>
                    No results for “{trimmedQuery}”
                  </Combobox.Empty>
                )}
                {options}
              </Combobox.Options>
            </Combobox.Dropdown>
          </Combobox>

          {!trimmedQuery && !selected && (
            <Text c="dimmed" size="sm">
              Type a book title or author to search Open Library.
            </Text>
          )}

          {selected && (
            <Paper withBorder radius="md" p="sm">
              <Group wrap="nowrap" align="flex-start" gap="sm">
                <CoverThumb
                  coverId={selected.cover_id}
                  title={selected.title}
                  width={60}
                  height={90}
                />
                <Stack gap={2} style={{ flex: 1, minWidth: 0 }}>
                  <Text size="xs" c="dimmed" tt="uppercase">
                    Selected
                  </Text>
                  <Text fw={600} lineClamp={2}>
                    {selected.title}
                  </Text>
                  <Text size="sm" c="dimmed" lineClamp={1}>
                    {formatAuthorsAndYear(selected)}
                  </Text>
                </Stack>
                <ActionIcon
                  variant="subtle"
                  color="gray"
                  onClick={() => setSelected(null)}
                  aria-label="Clear selected book"
                >
                  <IconX size={16} />
                </ActionIcon>
              </Group>
            </Paper>
          )}

          <Divider />

          <Stack gap="sm">
            <DatePickerInput
              label="Finished date"
              placeholder="Select date"
              valueFormat="DD MMM YYYY"
              value={finishedDate}
              onChange={(value) => setFinishedDate(value)}
            />
            <Button
              onClick={handleSubmit}
              loading={submitting}
              disabled={submitDisabled}
            >
              Add to library
            </Button>
            {!selected && (
              <Text size="xs" c="dimmed">
                Pick a result from the search to enable saving.
              </Text>
            )}
          </Stack>
        </Stack>
      </Container>
    </AppShellLayout>
  );
}
