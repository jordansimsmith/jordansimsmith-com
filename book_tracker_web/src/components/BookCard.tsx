import { useState } from 'react';
import { Card, Image, Stack, Text, Box, AspectRatio } from '@mantine/core';
import type { Book } from '../api/client';

interface BookCardProps {
  book: Book;
  onClick?: (book: Book) => void;
}

function CoverFallback({ title }: { title: string }) {
  return (
    <Box
      style={{
        width: '100%',
        height: '100%',
        background: 'var(--mantine-color-violet-light)',
        color: 'var(--mantine-color-violet-light-color)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 'var(--mantine-spacing-sm)',
        textAlign: 'center',
      }}
    >
      <Text fw={600} size="sm" lineClamp={4}>
        {title}
      </Text>
    </Box>
  );
}

export function BookCard({ book, onClick }: BookCardProps) {
  const [imageFailed, setImageFailed] = useState(false);
  const showFallback = !book.cover_url || imageFailed;
  const authorsLabel = book.authors.length > 0 ? book.authors.join(', ') : null;

  return (
    <Card
      withBorder
      padding="sm"
      radius="md"
      style={{ cursor: onClick ? 'pointer' : undefined }}
      onClick={onClick ? () => onClick(book) : undefined}
      role={onClick ? 'button' : undefined}
      aria-label={onClick ? `Edit ${book.title}` : undefined}
    >
      <Card.Section>
        <AspectRatio ratio={2 / 3}>
          {showFallback ? (
            <CoverFallback title={book.title} />
          ) : (
            <Image
              src={book.cover_url ?? undefined}
              alt={`Cover of ${book.title}`}
              onError={() => setImageFailed(true)}
              fit="cover"
            />
          )}
        </AspectRatio>
      </Card.Section>
      <Stack gap={2} mt="sm">
        <Text fw={600} size="sm" lineClamp={2}>
          {book.title}
        </Text>
        {authorsLabel && (
          <Text size="xs" c="dimmed" lineClamp={1}>
            {authorsLabel}
          </Text>
        )}
      </Stack>
    </Card>
  );
}
