import { Group, Stack, Text, Title } from '@mantine/core';
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
      <Group justify="space-between" wrap="nowrap" align="flex-end">
        <Group gap="md" align="baseline" wrap="wrap">
          <Title order={2} lang="ja" style={{ fontSize: '1.75rem' }}>
            {result.expression}
          </Title>
          {showReading && (
            <Text lang="ja" size="lg" c="dimmed">
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
        <Group gap="sm" align="center">
          <PitchGraph reading={result.reading} pitch={result.pitch} />
          <Text size="xs" c="dimmed">
            {getPitchPattern(result.reading, result.pitch)}
          </Text>
        </Group>
      )}
      <div>
        <GlossaryRenderer
          node={result.glossary_raw}
          onInternalNavigate={onInternalNavigate}
        />
      </div>
    </Stack>
  );
}
