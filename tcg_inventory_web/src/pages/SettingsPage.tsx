import { useEffect, useState } from 'react';
import {
  Button,
  Group,
  Paper,
  PasswordInput,
  Skeleton,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { DateInput } from '@mantine/dates';
import { notifications } from '@mantine/notifications';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { apiClient } from '../api/client';
import type {
  GetFetchTcgAccountResponse,
  SettingsResponse,
} from '../api/client';
import { PageHeader } from '../components/PageHeader';
import classes from './SettingsPage.module.css';

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
  const [settingsLoading, setSettingsLoading] = useState(true);
  const [settingsError, setSettingsError] = useState<string | null>(null);
  const [account, setAccount] = useState<GetFetchTcgAccountResponse | null>(
    null,
  );
  const [accountLoading, setAccountLoading] = useState(true);
  const [accountError, setAccountError] = useState<string | null>(null);
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
        }
      } catch (e) {
        if (!cancelled) {
          setSettingsError(
            e instanceof Error ? e.message : 'Failed to load settings',
          );
        }
      } finally {
        if (!cancelled) {
          setSettingsLoading(false);
        }
      }
    };

    const fetchAccount = async () => {
      try {
        const response = await apiClient.getFetchTcgAccount();
        if (!cancelled) {
          setAccount(response);
        }
      } catch (e) {
        if (!cancelled) {
          setAccountError(
            e instanceof Error ? e.message : 'Failed to load FetchTCG account',
          );
        }
      } finally {
        if (!cancelled) {
          setAccountLoading(false);
        }
      }
    };

    fetchSettings();
    fetchAccount();
    return () => {
      cancelled = true;
    };
  }, []);

  const handleSaveToken = async () => {
    setSavingToken(true);
    try {
      const response = await apiClient.updateFetchTcgCredentials({
        refresh_token: refreshToken,
      });
      setAccount(response);
      setAccountError(null);
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
        <div className={classes.grid}>
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
              {accountLoading && <Skeleton height={36} />}
              {!accountLoading && accountError && (
                <Text c="red">{accountError}</Text>
              )}
              {!accountLoading && !accountError && account && (
                <>
                  <TextInput
                    label="FetchTCG seller username"
                    value={account.seller_username}
                    description="Verified by FetchTCG; updates when you save a refresh token."
                    readOnly
                    disabled
                    styles={{
                      input: { color: 'var(--mantine-color-text)', opacity: 1 },
                    }}
                  />
                  <Text size="sm" c="dimmed">
                    Last updated{' '}
                    {new Date(
                      account.credentials_updated_at * 1000,
                    ).toLocaleString()}
                  </Text>
                </>
              )}
              {!accountLoading && !accountError && !account && (
                <Text size="sm" c="dimmed">
                  Save or re-save your FetchTCG refresh token to verify your
                  seller account.
                </Text>
              )}
              <PasswordInput
                label="FetchTCG refresh token"
                value={refreshToken}
                onChange={(event) => setRefreshToken(event.currentTarget.value)}
                placeholder={
                  account ? '••••••••••••••••' : 'Enter refresh token'
                }
              />
              <Group className={classes.sectionActions}>
                <Button
                  onClick={handleSaveToken}
                  loading={savingToken}
                  disabled={accountLoading || refreshToken.trim() === ''}
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
              {settingsLoading && <Skeleton height={36} />}
              {!settingsLoading && settingsError && (
                <Text c="red">{settingsError}</Text>
              )}
              {!settingsLoading && !settingsError && settings && (
                <DateInput
                  label="Track orders after"
                  value={trackOrdersAfter ?? ''}
                  onChange={(value) => setTrackOrdersAfter(value || null)}
                  placeholder="No cutoff set"
                />
              )}
              <Group className={classes.sectionActions}>
                <Button
                  onClick={handleSaveDate}
                  loading={savingDate}
                  disabled={
                    settingsLoading ||
                    settingsError !== null ||
                    settings === null ||
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
      </Stack>
    </AppShellLayout>
  );
}
