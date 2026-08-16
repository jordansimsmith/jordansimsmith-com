import { useEffect, useState } from 'react';
import {
  Button,
  Group,
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
      <Stack gap="xl" maw={480}>
        <Title order={2}>Settings</Title>
        {loading && (
          <Stack gap="xs">
            <Skeleton height={20} width={280} />
            <Skeleton height={56} />
          </Stack>
        )}
        {!loading && error && <Text c="red">{error}</Text>}
        {!loading && !error && settings && (
          <>
            <Stack gap="sm">
              <PasswordInput
                label="FetchTCG refresh token"
                description={
                  settings.credential_set && settings.updated_at !== null
                    ? `Last updated ${new Date(
                        settings.updated_at * 1000,
                      ).toLocaleString()}`
                    : undefined
                }
                value={refreshToken}
                onChange={(event) => setRefreshToken(event.currentTarget.value)}
                placeholder={
                  settings.credential_set
                    ? '••••••••••••••••'
                    : 'Enter refresh token'
                }
              />
              <Group>
                <Button
                  onClick={handleSaveToken}
                  loading={savingToken}
                  disabled={refreshToken.trim() === ''}
                >
                  Save
                </Button>
              </Group>
            </Stack>

            <Stack gap="sm">
              <DateInput
                label="Track orders after"
                description="Orders accepted on FetchTCG from this date onwards are tracked (the date itself is included, starting at midnight in your local timezone); anything accepted earlier is ignored."
                value={trackOrdersAfter ?? ''}
                onChange={(value) => setTrackOrdersAfter(value || null)}
                placeholder="Select date"
                clearable
              />
              <Group>
                <Button
                  onClick={handleSaveDate}
                  loading={savingDate}
                  disabled={!trackOrdersAfter}
                >
                  Save
                </Button>
              </Group>
            </Stack>
          </>
        )}
      </Stack>
    </AppShellLayout>
  );
}
