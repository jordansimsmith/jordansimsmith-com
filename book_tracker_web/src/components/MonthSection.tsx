import { SimpleGrid, Stack, Title } from '@mantine/core';
import type { Book } from '../api/client';
import { formatMonth } from '../domain/dates';
import { BookCard } from './BookCard';

interface MonthSectionProps {
  yearMonth: string;
  books: Book[];
  onBookClick?: (book: Book) => void;
}

export function MonthSection({
  yearMonth,
  books,
  onBookClick,
}: MonthSectionProps) {
  return (
    <Stack gap="sm">
      <Title order={3}>{formatMonth(yearMonth)}</Title>
      <SimpleGrid cols={{ base: 2, xs: 3, sm: 4, md: 5, lg: 6 }} spacing="md">
        {books.map((book) => (
          <BookCard
            key={book.open_library_work_id}
            book={book}
            onClick={onBookClick}
          />
        ))}
      </SimpleGrid>
    </Stack>
  );
}
