import { useEffect, useState } from 'react';
import {
  Container,
  Title,
  Text,
  Button,
  Group,
  Stack,
  Skeleton,
  Badge,
} from '@mantine/core';
import { IconBook2 } from '@tabler/icons-react';
import { notifications } from '@mantine/notifications';
import { useNavigate } from 'react-router-dom';
import { apiClient } from '../api/client';
import type { Book } from '../api/client';
import { clearSession, getSession } from '../auth/session';
import { MonthSection } from '../components/MonthSection';
import { monthKey } from '../domain/dates';

interface MonthGroup {
  yearMonth: string;
  books: Book[];
}

function groupByMonth(books: Book[]): MonthGroup[] {
  const groups = new Map<string, Book[]>();
  for (const book of books) {
    const key = monthKey(book.finished_date);
    const existing = groups.get(key);
    if (existing) {
      existing.push(book);
    } else {
      groups.set(key, [book]);
    }
  }

  return Array.from(groups.entries())
    .sort((a, b) => b[0].localeCompare(a[0]))
    .map(([yearMonth, booksInMonth]) => ({ yearMonth, books: booksInMonth }));
}

function EmptyState() {
  return (
    <Stack align="center" gap="md" py="xl">
      <IconBook2 size={64} stroke={1.5} color="var(--mantine-color-dimmed)" />
      <Text c="dimmed" ta="center">
        No finished books yet. Add your first book to start your timeline.
      </Text>
    </Stack>
  );
}

export function BooksPage() {
  const navigate = useNavigate();
  const session = getSession();
  const [books, setBooks] = useState<Book[]>([]);
  const [rollingCount, setRollingCount] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const fetchBooks = async () => {
      try {
        const response = await apiClient.getBooks();
        if (cancelled) {
          return;
        }
        setBooks(response.books);
        setRollingCount(response.rolling_12_month_count);
      } catch (e) {
        const message = e instanceof Error ? e.message : 'Failed to load books';
        if (cancelled) {
          return;
        }
        setError(message);
        notifications.show({ title: 'Error', message, color: 'red' });
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    fetchBooks();
    return () => {
      cancelled = true;
    };
  }, []);

  const handleLogout = () => {
    clearSession();
    navigate('/');
  };

  const groups = groupByMonth(books);

  return (
    <Container size="lg" py="xl">
      <Stack gap="lg">
        <Group justify="space-between">
          <Group gap="md">
            <Title order={1}>Books</Title>
            {rollingCount !== null && (
              <Badge size="lg" variant="light">
                {rollingCount} in last 12 months
              </Badge>
            )}
          </Group>
          <Group gap="sm">
            {session && (
              <Text size="sm" c="dimmed">
                {session.username}
              </Text>
            )}
            <Button variant="subtle" onClick={handleLogout}>
              Log out
            </Button>
          </Group>
        </Group>

        {loading && (
          <Stack gap="md">
            <Skeleton height={28} width={120} />
            <Skeleton height={220} />
          </Stack>
        )}

        {!loading && error && (
          <Text c="red" ta="center">
            {error}
          </Text>
        )}

        {!loading && !error && books.length === 0 && <EmptyState />}

        {!loading && !error && books.length > 0 && (
          <Stack gap="xl">
            {groups.map((group) => (
              <MonthSection
                key={group.yearMonth}
                yearMonth={group.yearMonth}
                books={group.books}
              />
            ))}
          </Stack>
        )}
      </Stack>
    </Container>
  );
}
