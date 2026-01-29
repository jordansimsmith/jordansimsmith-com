import { Stack, Title, Text, Paper } from '@mantine/core';
import { CumulativeHoursChart } from './CumulativeHoursChart';
import type {
  SummaryViewModel,
  ChartPoint,
} from '../presenters/progress-presenter';

interface ProgressSummaryProps {
  summary: SummaryViewModel;
  chartPoints: ChartPoint[];
}

export function ProgressSummary({
  summary,
  chartPoints,
}: ProgressSummaryProps) {
  return (
    <Paper p="lg" radius="md" withBorder>
      <Stack gap="md">
        <Stack gap={4} align="center">
          <Title order={1} fw={700}>
            {summary.totalHoursWatched.toLocaleString()}
          </Title>
          <Text c="dimmed" size="sm">
            total hours watched
          </Text>
        </Stack>
        <CumulativeHoursChart points={chartPoints} />
      </Stack>
    </Paper>
  );
}
