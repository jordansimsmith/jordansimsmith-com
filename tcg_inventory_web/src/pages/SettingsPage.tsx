import { useEffect, useState } from 'react';
import {
  Button,
  Group,
  Paper,
  PasswordInput,
  Skeleton,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import { DateInput } from '@mantine/dates';
import { notifications } from '@mantine/notifications';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { apiClient } from '../api/client';
import type { SettingsResponse } from '../api/client';
import { PageHeader } from '../components/PageHeader';

function epochToDateString(epoch: number): string {
  const date = new Date(epoch * 1000);
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

function dateStringToEpoch(dateStr: string): number {
  const [y, m, d] = dateStr.split('-').map(Number);
  return Math.floor(new Date(y, m - 1, d).getTime() / 1000);
}

export function SettingsPage() {
  const [settings, setSettings] = useState<SettingsResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refreshToken, setRefreshToken] = useState('');
  const [savingToken, setSavingToken] = useState(false);
  const [trackOrdersAfter, setTrackOrdersAfter] = useState<string | null>(null);
  const [savingDate, setSavingDate] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const fetchSettings = async () => {
      try {
        const response = await apiClient.getSettings();
        if (!cancelled) {
          setSettings(response);
          if (response.track_orders_after !== null) {
            setTrackOrdersAfter(epochToDateString(response.track_orders_after));
          }
          setError(null);
        }
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : 'Failed to load settings');
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    fetchSettings();
    return () => {
      cancelled = true;
    };
  }, []);

  const handleSaveToken = async () => {
    setSavingToken(true);
    try {
      const response = await apiClient.updateSettings({
        refresh_token: refreshToken,
      });
      setSettings(response);
      setRefreshToken('');
      notifications.show({
        title: 'Settings saved',
        message: 'FetchTCG refresh token updated',
        color: 'green',
      });
    } catch (e) {
      const message =
        e instanceof Error ? e.message : 'Failed to save credential';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setSavingToken(false);
    }
  };

  const handleSaveDate = async () => {
    if (!trackOrdersAfter) return;
    setSavingDate(true);
    try {
      const epochSeconds = dateStringToEpoch(trackOrdersAfter);
      const response = await apiClient.updateSettings({
        track_orders_after: epochSeconds,
      });
      setSettings(response);
      notifications.show({
        title: 'Settings saved',
        message: 'Track orders after date updated',
        color: 'green',
      });
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Failed to save date';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setSavingDate(false);
    }
  };

  return (
    <AppShellLayout>
      <Stack gap="lg">
        <PageHeader
          title="Settings"
          description="Configure marketplace access and order tracking."
        />
        {loading && (
          <div className="settings-grid">
            {[0, 1].map((item) => (
              <Paper key={item} withBorder p="md" radius="md">
                <Stack gap="md">
                  <Skeleton height={20} width={180} />
                  <Skeleton height={36} />
                  <Skeleton height={18} width={160} />
                  <Skeleton height={36} />
                  <Skeleton height={36} width={110} />
                </Stack>
              </Paper>
            ))}
          </div>
        )}
        {!loading && error && (
          <Paper withBorder p="md" radius="md">
            <Text c="red">{error}</Text>
          </Paper>
        )}
        {!loading && !error && settings && (
          <div className="settings-grid">
            <Paper
              component="section"
              aria-label="FetchTCG connection"
              withBorder
              p="md"
              radius="md"
            >
              <Stack gap="md" h="100%">
                <Stack gap={4}>
                  <Title order={3} fz="md">
                    FetchTCG connection
                  </Title>
                  <Text size="sm" c="dimmed">
                    Save a token for FetchTCG publishing and order sync.
                  </Text>
                </Stack>
                {settings.credential_set && settings.updated_at !== null && (
                  <Text size="sm" c="dimmed">
                    Last updated{' '}
                    {new Date(settings.updated_at * 1000).toLocaleString()}
                  </Text>
                )}
                <PasswordInput
                  label="FetchTCG refresh token"
                  value={refreshToken}
                  onChange={(event) =>
                    setRefreshToken(event.currentTarget.value)
                  }
                  placeholder={
                    settings.credential_set
                      ? '••••••••••••••••'
                      : 'Enter refresh token'
                  }
                />
                <Group className="settings-section-actions">
                  <Button
                    onClick={handleSaveToken}
                    loading={savingToken}
                    disabled={refreshToken.trim() === ''}
                  >
                    Save token
                  </Button>
                </Group>
              </Stack>
            </Paper>

            <Paper
              component="section"
              aria-label="Order tracking"
              withBorder
              p="md"
              radius="md"
            >
              <Stack gap="md" h="100%">
                <Stack gap={4}>
                  <Title order={3} fz="md">
                    Order tracking
                  </Title>
                  <Text size="sm" c="dimmed">
                    Orders accepted on or after this date are tracked; earlier
                    orders are ignored. The cutoff starts at midnight in your
                    local timezone.
                  </Text>
                </Stack>
                <DateInput
                  label="Track orders after"
                  value={trackOrdersAfter ?? ''}
                  onChange={(value) => setTrackOrdersAfter(value || null)}
                  placeholder="No cutoff set"
                />
                <Group className="settings-section-actions">
                  <Button
                    onClick={handleSaveDate}
                    loading={savingDate}
                    disabled={
                      !trackOrdersAfter ||
                      trackOrdersAfter ===
                        (settings.track_orders_after === null
                          ? null
                          : epochToDateString(settings.track_orders_after))
                    }
                  >
                    Save date
                  </Button>
                </Group>
              </Stack>
            </Paper>
          </div>
        )}
      </Stack>
    </AppShellLayout>
  );
}
