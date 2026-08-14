import { useEffect, useState } from 'react';
import { Badge, Button, Group, Progress, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { apiClient } from '../api/client';
import type { PublishResponse } from '../api/client';

const POLL_INTERVAL_MS = 2000;

export function PublishWidget() {
  const [publish, setPublish] = useState<PublishResponse | null>(null);
  const [triggering, setTriggering] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pollEpoch, setPollEpoch] = useState(0);

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const poll = async () => {
      try {
        const response = await apiClient.getPublish();
        if (cancelled) {
          return;
        }
        setPublish(response);
        setError(null);
        if (response.status === 'queued' || response.status === 'running') {
          timer = setTimeout(poll, POLL_INTERVAL_MS);
        }
      } catch (e) {
        if (cancelled) {
          return;
        }
        const message = e instanceof Error ? e.message : '';
        if (message === 'Not Found') {
          setPublish({
            status: null,
            published_sku_count: 0,
            total_sku_count: 0,
            error: null,
            started_at: null,
            finished_at: null,
            pending_sku_count: 0,
          });
          setError(null);
        } else {
          setError(message || 'Failed to load publish status');
        }
      }
    };

    poll();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [pollEpoch]);

  const handleTrigger = async () => {
    setTriggering(true);
    try {
      const response = await apiClient.createPublish();
      setPublish(response);
      setError(null);
      setPollEpoch((epoch) => epoch + 1);
    } catch (e) {
      const message =
        e instanceof Error ? e.message : 'Failed to start publish';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setTriggering(false);
    }
  };

  const runActive =
    publish?.status === 'queued' || publish?.status === 'running';
  const pendingCount = publish?.pending_sku_count ?? 0;

  return (
    <Group gap="sm" wrap="nowrap" align="center">
      {!runActive &&
        publish?.status === 'succeeded' &&
        publish.finished_at !== null && (
          <Text size="sm" c="dimmed">
            Last publish succeeded{' '}
            {new Date(publish.finished_at * 1000).toLocaleString()}
          </Text>
        )}
      {!runActive && publish?.status === 'failed' && (
        <Text size="sm" c="red" maw={360}>
          Publish failed: {publish.error}
        </Text>
      )}
      {error && (
        <Text size="sm" c="red">
          {error}
        </Text>
      )}
      {runActive && publish ? (
        <Stack gap={4} w={240}>
          <Text size="sm">
            Publishing {publish.published_sku_count} of{' '}
            {publish.total_sku_count}
          </Text>
          <Progress
            value={
              publish.total_sku_count > 0
                ? (publish.published_sku_count / publish.total_sku_count) * 100
                : 100
            }
            animated
          />
        </Stack>
      ) : (
        <Button
          onClick={handleTrigger}
          disabled={publish === null}
          loading={triggering}
          rightSection={
            pendingCount > 0 ? (
              <Badge size="sm" c="white" bg="rgba(255, 255, 255, 0.25)">
                {pendingCount}
              </Badge>
            ) : undefined
          }
        >
          Publish
        </Button>
      )}
    </Group>
  );
}
