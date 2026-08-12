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
import { notifications } from '@mantine/notifications';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { apiClient } from '../api/client';
import type { SettingsResponse } from '../api/client';

export function SettingsPage() {
  const [settings, setSettings] = useState<SettingsResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refreshToken, setRefreshToken] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const fetchSettings = async () => {
      try {
        const response = await apiClient.getSettings();
        if (!cancelled) {
          setSettings(response);
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

  const handleSave = async () => {
    setSaving(true);
    try {
      const response = await apiClient.updateSettings(refreshToken);
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
      setSaving(false);
    }
  };

  return (
    <AppShellLayout>
      <Stack gap="md" maw={480}>
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
                onClick={handleSave}
                loading={saving}
                disabled={refreshToken.trim() === ''}
              >
                Save
              </Button>
            </Group>
          </>
        )}
      </Stack>
    </AppShellLayout>
  );
}
