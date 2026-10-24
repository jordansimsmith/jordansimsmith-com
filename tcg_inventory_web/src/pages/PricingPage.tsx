import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Badge,
  Button,
  Group,
  Paper,
  Progress,
  Skeleton,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { apiClient } from '../api/client';
import type { PricingResponse, RepriceResponse } from '../api/client';
import { PageHeader } from '../components/PageHeader';
import { JobFailureAlert } from '../components/JobFailureAlert';

dayjs.extend(relativeTime);

export function PricingPage() {
  const [pricing, setPricing] = useState<PricingResponse | null>(null);
  const [priceFloor, setPriceFloor] = useState('');
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [reprice, setReprice] = useState<RepriceResponse | null>(null);
  const [statusLoaded, setStatusLoaded] = useState(false);
  const [statusError, setStatusError] = useState<string | null>(null);
  const [triggering, setTriggering] = useState(false);
  const [awaitingStatus, setAwaitingStatus] = useState(false);
  const mounted = useRef(false);
  const reading = useRef(false);
  const runStatus = awaitingStatus ? 'queued' : reprice?.status;
  const runActive = runStatus === 'queued' || runStatus === 'running';

  const refreshStatus = useCallback(async () => {
    if (reading.current) return;
    reading.current = true;
    try {
      const response = await apiClient.getReprice();
      if (!mounted.current) return;
      setReprice(response);
      setAwaitingStatus(false);
      setStatusError(null);
    } catch (e) {
      if (mounted.current) {
        setStatusError(
          e instanceof Error ? e.message : 'Failed to load repricing status',
        );
      }
    } finally {
      reading.current = false;
      if (mounted.current) setStatusLoaded(true);
    }
  }, []);

  useEffect(() => {
    mounted.current = true;
    void refreshStatus();
    window.addEventListener('focus', refreshStatus);
    return () => {
      mounted.current = false;
      window.removeEventListener('focus', refreshStatus);
    };
  }, [refreshStatus]);

  useEffect(() => {
    if (!runActive) return;
    const timer = window.setInterval(() => void refreshStatus(), 2000);
    return () => window.clearInterval(timer);
  }, [runActive, refreshStatus]);

  useEffect(() => {
    let cancelled = false;
    async function loadPricing() {
      try {
        const response = await apiClient.getPricing();
        if (!cancelled) {
          setPricing(response);
          setPriceFloor(response.price_floor);
        }
      } catch (e) {
        if (!cancelled)
          setLoadError(
            e instanceof Error ? e.message : 'Failed to load pricing',
          );
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    void loadPricing();
    return () => {
      cancelled = true;
    };
  }, []);

  const handleSave = async () => {
    setSaving(true);
    try {
      const response = await apiClient.updatePricing({
        price_floor: priceFloor,
      });
      setPricing(response);
      setPriceFloor(response.price_floor);
      notifications.show({
        title: 'Pricing saved',
        message: 'Price floor updated for future appraisals and repricing',
        color: 'green',
      });
    } catch (e) {
      notifications.show({
        title: 'Error',
        message: e instanceof Error ? e.message : 'Failed to save price floor',
        color: 'red',
      });
      await refreshStatus();
    } finally {
      setSaving(false);
    }
  };

  const handleReprice = async () => {
    setTriggering(true);
    try {
      await apiClient.createReprice();
      setReprice(null);
      setAwaitingStatus(true);
    } catch (e) {
      notifications.show({
        title: 'Error',
        message: e instanceof Error ? e.message : 'Failed to start repricing',
        color: 'red',
      });
    } finally {
      await refreshStatus();
      setTriggering(false);
    }
  };

  const unchanged =
    pricing !== null && priceFloor.trim() === pricing.price_floor;
  const unsaved = pricing !== null && !unchanged;
  const headlines = {
    queued: 'Repricing queued',
    running: 'Repricing inventory',
    succeeded: 'Repricing complete',
    failed: 'Repricing needs attention',
  };
  const headline = !statusLoaded
    ? 'Checking repricing status…'
    : runStatus
      ? headlines[runStatus]
      : statusError
        ? 'Repricing status unavailable'
        : 'Ready to reprice inventory';
  const latestTime = reprice?.finished_at ?? reprice?.started_at;

  return (
    <AppShellLayout>
      <Stack gap="lg">
        <PageHeader
          title="Pricing"
          description="Set your pricing rules and apply them to inventory."
        />
        <Paper
          component="section"
          aria-label="Pricing settings"
          withBorder
          p="md"
          radius="md"
        >
          <Stack gap="md">
            <Stack gap="xs">
              <Text size="xs" fw={600} c="dimmed" tt="uppercase">
                Pricing settings
              </Text>
              <Title order={2} fz="lg">
                Minimum suggested price
              </Title>
              <Text size="sm" c="dimmed">
                The lowest price used for new appraisals and inventory
                repricing.
              </Text>
            </Stack>
            {loading ? (
              <Skeleton height={60} />
            ) : loadError ? (
              <Text role="alert" c="red">
                Failed to load pricing settings: {loadError}
              </Text>
            ) : (
              pricing && (
                <>
                  <Group align="flex-end" gap="sm">
                    <TextInput
                      label="Price floor (NZD)"
                      value={priceFloor}
                      onChange={(event) =>
                        setPriceFloor(event.currentTarget.value)
                      }
                      inputMode="decimal"
                      autoComplete="off"
                      disabled={runActive || saving || triggering}
                      style={{ flex: '1 1 180px', maxWidth: 280 }}
                    />
                    <Button
                      onClick={handleSave}
                      loading={saving}
                      disabled={
                        runActive ||
                        triggering ||
                        !statusLoaded ||
                        !!statusError ||
                        priceFloor.trim() === '' ||
                        unchanged
                      }
                    >
                      Save price floor
                    </Button>
                    {unsaved && (
                      <Text size="xs" c="dimmed" pb={8}>
                        Unsaved changes
                      </Text>
                    )}
                  </Group>
                  <Text size="sm" c="dimmed">
                    {runActive
                      ? 'Settings are locked while repricing is active.'
                      : 'Saving applies to future appraisals. Reprice below to update existing stock.'}
                  </Text>
                </>
              )
            )}
            <Text
              size="xs"
              c="dimmed"
              pt="sm"
              style={{ borderTop: '1px solid var(--mantine-color-gray-3)' }}
            >
              Default floor: NZ$0.25. The separate appraisal rule still discards
              cards with a market value below NZ$0.25.
            </Text>
          </Stack>
        </Paper>
        <Paper
          component="section"
          aria-label="Repricing status"
          withBorder
          p="md"
          radius="md"
        >
          <Stack gap="md">
            <Group justify="space-between" align="flex-start" gap="md">
              <Stack gap="xs" style={{ flex: '1 1 280px', minWidth: 0 }}>
                <Group gap="xs">
                  <Text size="xs" fw={600} c="dimmed" tt="uppercase">
                    Inventory repricing
                  </Text>
                  {runStatus && (
                    <Badge
                      variant="light"
                      color={
                        runActive
                          ? 'blue'
                          : runStatus === 'failed'
                            ? 'red'
                            : 'green'
                      }
                    >
                      {runStatus === 'succeeded'
                        ? 'Completed'
                        : runStatus === 'running'
                          ? 'Repricing'
                          : runStatus}
                    </Badge>
                  )}
                </Group>
                <Title order={2} fz="lg" aria-live="polite">
                  {headline}
                </Title>
                <Text size="sm" c="dimmed">
                  Refresh prices for in-stock SKUs across all games using
                  current market and competitor prices. Your own listings are
                  excluded.
                </Text>
              </Stack>
              <Button
                onClick={handleReprice}
                loading={triggering}
                disabled={
                  !pricing ||
                  saving ||
                  unsaved ||
                  runActive ||
                  !statusLoaded ||
                  !!statusError
                }
              >
                {runActive ? 'Repricing' : 'Reprice inventory'}
              </Button>
            </Group>
            {unsaved && (
              <Text size="sm" c="dimmed">
                Save your price floor before starting a repricing run.
              </Text>
            )}
            {statusError && (
              <Group justify="space-between">
                <Text size="sm" c="red" role="alert">
                  {statusError}
                </Text>
                <Button
                  variant="default"
                  size="compact-sm"
                  onClick={() => void refreshStatus()}
                >
                  Retry status
                </Button>
              </Group>
            )}
            {reprice && runStatus === 'failed' && (
              <Stack gap="xs">
                <JobFailureAlert
                  title="Repricing failed"
                  error={reprice.error}
                />
                <Text size="sm" style={{ fontVariantNumeric: 'tabular-nums' }}>
                  {reprice.processed_sku_count.toLocaleString()} SKUs checked
                  before the run stopped. Price changes already made remain
                  ready to publish.
                </Text>
              </Stack>
            )}
            {runActive && (
              <Stack gap={6}>
                <Group justify="space-between" gap="xs">
                  <Text
                    size="sm"
                    aria-live="polite"
                    style={{ fontVariantNumeric: 'tabular-nums' }}
                  >
                    {reprice
                      ? `${reprice.processed_sku_count.toLocaleString()} SKUs checked`
                      : 'Waiting for the repricing run to start'}
                  </Text>
                  <Text size="xs" c="dimmed">
                    {runStatus === 'queued'
                      ? 'Waiting to start'
                      : 'Checking all games'}
                  </Text>
                </Group>
                <Progress
                  value={100}
                  animated
                  striped
                  aria-label="Repricing progress"
                  aria-valuenow={undefined}
                />
                <Text size="xs" c="dimmed">
                  You can leave this page. Repricing continues in the
                  background.
                </Text>
              </Stack>
            )}
            {reprice && runStatus === 'succeeded' && (
              <Text size="sm" style={{ fontVariantNumeric: 'tabular-nums' }}>
                {reprice.processed_sku_count === 0
                  ? 'No inventory needed repricing.'
                  : `${reprice.processed_sku_count.toLocaleString()} SKUs checked. In-stock prices are ready to publish.`}
              </Text>
            )}
            <Group
              justify="space-between"
              gap="sm"
              pt="sm"
              style={{ borderTop: '1px solid var(--mantine-color-gray-3)' }}
            >
              <Text size="sm" c="dimmed">
                {runActive ? 'Current run' : 'Latest run'}
              </Text>
              <Text size="sm" fw={500}>
                {latestTime
                  ? `${runActive ? 'Started' : runStatus === 'failed' ? 'Failed' : 'Completed'} ${dayjs(latestTime * 1000).fromNow()}`
                  : runActive
                    ? 'Queued'
                    : statusLoaded
                      ? statusError
                        ? 'Status unavailable'
                        : 'No previous run'
                      : 'Loading…'}
              </Text>
            </Group>
            <Text size="xs" c="dimmed">
              Repricing keeps all stock. Prices update on FetchTCG when you
              publish.
            </Text>
          </Stack>
        </Paper>
      </Stack>
    </AppShellLayout>
  );
}
