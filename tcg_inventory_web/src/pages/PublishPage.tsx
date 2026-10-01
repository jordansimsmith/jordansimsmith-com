import { useEffect, useState } from 'react';
import {
  Badge,
  Button,
  Group,
  Paper,
  Progress,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { PageHeader } from '../components/PageHeader';
import { JobFailureAlert } from '../components/JobFailureAlert';
import { usePublishStatus } from '../PublishStatusProvider';
import type { PublishResponse } from '../api/client';

dayjs.extend(relativeTime);

const POLL_INTERVAL_MS = 2000;

function getPublishPresentation(
  publish: PublishResponse | null,
  statusError: string | null | undefined,
  latestRunRelativeTime: string | null,
) {
  if (publish === null) {
    return {
      headline: statusError
        ? 'Publish status needs attention'
        : 'Checking publish status…',
      description: statusError
        ? 'Retry the status check to see the latest publish information.'
        : 'Loading the current publish status.',
      statusLabel: null,
      statusColor: 'gray',
      runContextLabel: 'Latest run',
      runContextValue: statusError ? 'Status unavailable' : 'Loading…',
    };
  }

  if (publish.status === null) {
    return {
      headline:
        publish.pending_sku_count > 0
          ? `${publish.pending_sku_count} SKUs ready to publish`
          : 'No previous publish run',
      description:
        publish.pending_sku_count > 0
          ? 'Publish these inventory changes to FetchTCG.'
          : 'Start a run to sync the current inventory.',
      statusLabel: null,
      statusColor: 'gray',
      runContextLabel: 'Last successful publish',
      runContextValue: 'Never',
    };
  }

  if (publish.status === 'queued') {
    return {
      headline: 'Publish run queued',
      description: 'Your inventory changes are waiting to publish.',
      statusLabel: 'Queued',
      statusColor: 'blue',
      runContextLabel: 'Current run',
      runContextValue: 'Queued',
    };
  }

  if (publish.status === 'running') {
    return {
      headline: 'Publishing inventory changes',
      description: 'This page will update as each SKU is published.',
      statusLabel: 'Publishing',
      statusColor: 'blue',
      runContextLabel: 'Current run',
      runContextValue:
        latestRunRelativeTime === null
          ? 'In progress'
          : `Started ${latestRunRelativeTime}`,
    };
  }

  if (publish.status === 'failed') {
    return {
      headline:
        publish.pending_sku_count > 0
          ? `${publish.pending_sku_count} SKUs still need publishing`
          : 'Latest publish needs attention',
      description:
        publish.pending_sku_count > 0
          ? `Resolve the issue below, then republish ${publish.pending_sku_count} pending SKUs.`
          : 'Resolve the issue below, then try publishing again.',
      statusLabel: 'Failed',
      statusColor: 'red',
      runContextLabel: 'Latest run',
      runContextValue:
        latestRunRelativeTime === null
          ? 'Failed'
          : `Failed ${latestRunRelativeTime}`,
    };
  }

  return {
    headline:
      publish.pending_sku_count > 0
        ? `${publish.pending_sku_count} SKUs ready to publish`
        : 'Inventory is up to date',
    description:
      publish.pending_sku_count > 0
        ? 'Publish these inventory changes to FetchTCG.'
        : publish.total_sku_count === 0
          ? 'No inventory changes needed publishing.'
          : `Published ${publish.total_sku_count} SKUs${latestRunRelativeTime ? ` ${latestRunRelativeTime}` : ''}.`,
    statusLabel: 'Completed',
    statusColor: 'green',
    runContextLabel: 'Last successful publish',
    runContextValue: latestRunRelativeTime ?? 'Completed',
  };
}

export function PublishPage() {
  const publishStatus = usePublishStatus();
  const refresh = publishStatus?.refresh;
  const [triggering, setTriggering] = useState(false);
  const publish = publishStatus?.publish ?? null;
  const runActive =
    publish?.status === 'queued' || publish?.status === 'running';

  useEffect(() => {
    if (!runActive || !refresh) {
      return;
    }

    const timer = window.setTimeout(() => {
      void refresh();
    }, POLL_INTERVAL_MS);
    return () => window.clearTimeout(timer);
  }, [publish, refresh, runActive]);

  const latestRunTime = publish?.finished_at ?? publish?.started_at ?? null;
  const latestRunRelativeTime =
    latestRunTime === null ? null : dayjs(latestRunTime * 1000).fromNow();
  const presentation = getPublishPresentation(
    publish,
    publishStatus?.error,
    latestRunRelativeTime,
  );

  const handlePublish = async () => {
    if (!publishStatus) {
      return;
    }
    setTriggering(true);
    try {
      await publishStatus.startPublish();
    } catch (e) {
      const message =
        e instanceof Error ? e.message : 'Failed to start publish';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setTriggering(false);
    }
  };

  return (
    <AppShellLayout>
      <Stack gap="lg">
        <PageHeader
          title="Publish"
          description="Sync inventory changes with FetchTCG."
        />
        <Paper
          component="section"
          aria-label="Publish status"
          withBorder
          p="md"
          radius="md"
        >
          <Stack gap="md">
            <Group justify="space-between" align="flex-start" gap="md">
              <Stack gap="xs" style={{ minWidth: 0 }}>
                <Group gap="xs">
                  <Text size="xs" fw={600} c="dimmed" tt="uppercase">
                    Inventory status
                  </Text>
                  {presentation.statusLabel && (
                    <Badge variant="light" color={presentation.statusColor}>
                      {presentation.statusLabel}
                    </Badge>
                  )}
                </Group>
                <Title order={2} fz="lg" aria-live="polite">
                  {presentation.headline}
                </Title>
                <Text size="sm" c="dimmed">
                  {presentation.description}
                </Text>
              </Stack>
              <Button
                onClick={handlePublish}
                disabled={
                  publish === null || runActive || publishStatus?.error !== null
                }
                loading={triggering}
              >
                {runActive ? 'Publishing' : 'Publish changes'}
              </Button>
            </Group>

            {publishStatus?.error && (
              <Group justify="space-between" align="center" gap="md">
                <Text size="sm" c="red" role="alert">
                  {publishStatus.error}
                </Text>
                <Button
                  variant="default"
                  size="compact-sm"
                  onClick={() => void publishStatus.refresh()}
                >
                  Retry status
                </Button>
              </Group>
            )}

            {publish?.status === 'failed' && (
              <JobFailureAlert title="Publish failed" error={publish.error} />
            )}

            {publish?.status === 'queued' && (
              <Text size="sm" aria-live="polite">
                Waiting for the publish run to start
              </Text>
            )}

            {publish?.status === 'running' && (
              <Stack gap={4}>
                <Text size="sm" aria-live="polite">
                  Publishing {publish.published_sku_count} of{' '}
                  {publish.total_sku_count} SKUs
                </Text>
                <Progress
                  value={
                    publish.total_sku_count > 0
                      ? (publish.published_sku_count /
                          publish.total_sku_count) *
                        100
                      : 100
                  }
                  animated
                />
                {publish.pending_sku_count > 0 && (
                  <Text size="sm" c="dimmed">
                    {publish.pending_sku_count} SKUs still pending
                  </Text>
                )}
              </Stack>
            )}

            {publish && (
              <Group
                justify="space-between"
                gap="md"
                pt="sm"
                style={{ borderTop: '1px solid var(--mantine-color-gray-3)' }}
              >
                <Text size="sm" c="dimmed">
                  {presentation.runContextLabel}
                </Text>
                <Text size="sm" fw={500} aria-live="polite">
                  {presentation.runContextValue}
                </Text>
              </Group>
            )}
          </Stack>
        </Paper>
      </Stack>
    </AppShellLayout>
  );
}
