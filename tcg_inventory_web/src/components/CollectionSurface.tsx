import type { ReactNode } from 'react';
import { Box, Paper, Skeleton, Stack, Text } from '@mantine/core';

interface CollectionSurfaceProps {
  children: ReactNode;
  toolbar?: ReactNode;
  footer?: ReactNode;
  ariaLabel?: string;
}

export function CollectionSurface({
  children,
  toolbar,
  footer,
  ariaLabel,
}: CollectionSurfaceProps) {
  return (
    <Paper
      component="section"
      aria-label={ariaLabel}
      withBorder
      radius="md"
      style={{ overflow: 'hidden' }}
    >
      {toolbar && (
        <Box
          px="md"
          py="sm"
          bg="gray.0"
          style={{ borderBottom: '1px solid var(--mantine-color-gray-3)' }}
        >
          {toolbar}
        </Box>
      )}
      <Box style={{ minWidth: 0, overflowX: 'auto' }}>{children}</Box>
      {footer && (
        <Box
          px="md"
          py="sm"
          style={{ borderTop: '1px solid var(--mantine-color-gray-3)' }}
        >
          {footer}
        </Box>
      )}
    </Paper>
  );
}

export function CollectionLoadingState({ rows = 5 }: { rows?: number }) {
  return (
    <Stack gap="xs" p="md" aria-label="Loading collection">
      {Array.from({ length: rows }, (_, index) => (
        <Skeleton key={index} height={28} />
      ))}
    </Stack>
  );
}

interface CollectionMessageProps {
  title: string;
  description?: string;
  tone?: 'dimmed' | 'error';
}

export function CollectionMessage({
  title,
  description,
  tone = 'dimmed',
}: CollectionMessageProps) {
  const color = tone === 'error' ? 'red' : undefined;

  return (
    <Stack gap={4} align="center" justify="center" px="md" py={48}>
      <Text fw={600} c={color} ta="center">
        {title}
      </Text>
      {description && (
        <Text size="sm" c={tone === 'error' ? 'red.7' : 'dimmed'} ta="center">
          {description}
        </Text>
      )}
    </Stack>
  );
}
