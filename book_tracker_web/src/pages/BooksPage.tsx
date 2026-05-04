import { useEffect, useState } from 'react';
import {
  Container,
  Title,
  Text,
  Button,
  Group,
  Stack,
  Skeleton,
} from '@mantine/core';
import { IconBook2, IconPlus } from '@tabler/icons-react';
import { notifications } from '@mantine/notifications';
import { useNavigate } from 'react-router-dom';
import { ApiError, apiClient } from '../api/client';
import type { Book } from '../api/client';
import { MonthSection } from '../components/MonthSection';
import { EditBookModal } from '../components/EditBookModal';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { useLibraryStats } from '../layouts/library-stats';
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

function EmptyState({ onAdd }: { onAdd: () => void }) {
  return (
    <Stack align="center" gap="md" py="xl">
      <IconBook2 size={64} stroke={1.5} color="var(--mantine-color-dimmed)" />
      <Text c="dimmed" ta="center">
        No finished books yet. Add your first book to start your timeline.
      </Text>
      <Button leftSection={<IconPlus size={16} />} onClick={onAdd}>
        Add book
      </Button>
    </Stack>
  );
}

export function BooksPage() {
  const navigate = useNavigate();
  const { setRollingCount } = useLibraryStats();
  const [books, setBooks] = useState<Book[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<Book | null>(null);
  const [savingEdit, setSavingEdit] = useState(false);
  const [deletingEdit, setDeletingEdit] = useState(false);

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
  }, [setRollingCount]);

  const refreshBooks = async () => {
    const response = await apiClient.getBooks();
    setBooks(response.books);
    setRollingCount(response.rolling_12_month_count);
  };

  const handleEditSave = async (book: Book, finishedDate: string) => {
    setSavingEdit(true);
    try {
      await apiClient.updateBook(book.open_library_work_id, {
        finished_date: finishedDate,
      });
      await refreshBooks();
      notifications.show({
        title: 'Book updated',
        message: `${book.title} moved to ${finishedDate}`,
        color: 'green',
      });
      setEditing(null);
    } catch (e) {
      const message =
        e instanceof ApiError
          ? e.message
          : e instanceof Error
            ? e.message
            : 'Failed to update book';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setSavingEdit(false);
    }
  };

  const handleEditDelete = async (book: Book) => {
    setDeletingEdit(true);
    try {
      await apiClient.deleteBook(book.open_library_work_id);
      await refreshBooks();
      notifications.show({
        title: 'Book removed',
        message: `${book.title} removed from your library`,
        color: 'green',
      });
      setEditing(null);
    } catch (e) {
      const message =
        e instanceof ApiError
          ? e.message
          : e instanceof Error
            ? e.message
            : 'Failed to remove book';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setDeletingEdit(false);
    }
  };

  const groups = groupByMonth(books);

  return (
    <AppShellLayout>
      <Container size="lg" py="xl">
        <Stack gap="lg">
          <Group justify="space-between">
            <Title order={1}>Books</Title>
            <Button
              leftSection={<IconPlus size={16} />}
              onClick={() => navigate('/books/add')}
            >
              Add book
            </Button>
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

          {!loading && !error && books.length === 0 && (
            <EmptyState onAdd={() => navigate('/books/add')} />
          )}

          {!loading && !error && books.length > 0 && (
            <Stack gap="xl">
              {groups.map((group) => (
                <MonthSection
                  key={group.yearMonth}
                  yearMonth={group.yearMonth}
                  books={group.books}
                  onBookClick={setEditing}
                />
              ))}
            </Stack>
          )}
        </Stack>

        <EditBookModal
          book={editing}
          opened={editing !== null}
          onClose={() => setEditing(null)}
          onSave={handleEditSave}
          onDelete={handleEditDelete}
          saving={savingEdit}
          deleting={deletingEdit}
        />
      </Container>
    </AppShellLayout>
  );
}
