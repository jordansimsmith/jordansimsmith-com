import { Group, Skeleton, Stack, Text, Title } from '@mantine/core';
import type { SearchResult } from '../api/client';
import { GlossaryRenderer } from './GlossaryRenderer';
import { PitchGraph, getPitchPattern } from './PitchGraph';

interface ResultEntryProps {
  result: SearchResult;
  onInternalNavigate?: (q: string) => void;
}

export function ResultEntry({ result, onInternalNavigate }: ResultEntryProps) {
  const showReading =
    result.reading.length > 0 && result.reading !== result.expression;

  return (
    <Stack gap="xs">
      <Group justify="space-between" wrap="wrap" align="flex-end" gap="xs">
        <Group gap="md" align="baseline" wrap="wrap" style={{ minWidth: 0 }}>
          <Title
            order={2}
            lang="ja"
            style={{
              fontSize: '1.75rem',
              wordBreak: 'break-word',
              overflowWrap: 'anywhere',
            }}
          >
            {result.expression}
          </Title>
          {showReading && (
            <Text
              lang="ja"
              size="lg"
              c="dimmed"
              style={{ wordBreak: 'break-word', overflowWrap: 'anywhere' }}
            >
              {result.reading}
            </Text>
          )}
          <Text size="xs" c="dimmed">
            {result.reading_romaji}
          </Text>
        </Group>
        {result.frequency_rank !== null && (
          <Text size="sm" c="dimmed" style={{ whiteSpace: 'nowrap' }}>
            #{result.frequency_rank}
          </Text>
        )}
      </Group>
      {result.pitch !== null && (
        <Group gap="sm" align="center" wrap="wrap">
          <PitchGraph reading={result.reading} pitch={result.pitch} />
          <Text size="xs" c="dimmed">
            {getPitchPattern(result.reading, result.pitch)}
          </Text>
        </Group>
      )}
      <div style={{ overflowWrap: 'anywhere' }}>
        <GlossaryRenderer
          node={result.glossary_raw}
          onInternalNavigate={onInternalNavigate}
        />
      </div>
    </Stack>
  );
}

export function ResultEntrySkeleton() {
  return (
    <Stack gap="xs" aria-hidden>
      <Group justify="space-between" wrap="wrap" align="flex-end" gap="xs">
        <Group gap="md" align="baseline" wrap="wrap" style={{ minWidth: 0 }}>
          <Skeleton height={28} width={120} />
          <Skeleton height={20} width={80} />
          <Skeleton height={14} width={60} />
        </Group>
        <Skeleton height={14} width={40} />
      </Group>
      <Skeleton height={36} width={140} />
      <Stack gap={6} mt="xs">
        <Skeleton height={12} width="80%" />
        <Skeleton height={12} width="60%" />
      </Stack>
    </Stack>
  );
}
