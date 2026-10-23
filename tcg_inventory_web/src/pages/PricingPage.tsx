import { useEffect, useState } from 'react';
import {
  Button,
  Group,
  Paper,
  SimpleGrid,
  Skeleton,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { apiClient } from '../api/client';
import type { PricingResponse } from '../api/client';
import { PageHeader } from '../components/PageHeader';

export function PricingPage() {
  const [pricing, setPricing] = useState<PricingResponse | null>(null);
  const [priceFloor, setPriceFloor] = useState('');
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const loadPricing = async () => {
      try {
        const response = await apiClient.getPricing();
        if (!cancelled) {
          setPricing(response);
          setPriceFloor(response.price_floor);
        }
      } catch (e) {
        if (!cancelled) {
          setLoadError(
            e instanceof Error ? e.message : 'Failed to load pricing',
          );
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

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
        message: 'Price floor updated for future appraisals',
        color: 'green',
      });
    } catch (e) {
      const message =
        e instanceof Error ? e.message : 'Failed to save price floor';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setSaving(false);
    }
  };

  const unchanged =
    pricing !== null && priceFloor.trim() === pricing.price_floor;

  return (
    <AppShellLayout>
      <Stack gap="lg">
        <PageHeader title="Pricing" />
        <Paper
          component="section"
          aria-label="Appraisal price floor"
          withBorder
          p="md"
          radius="md"
        >
          {loading ? (
            <SimpleGrid cols={{ base: 1, md: 2 }} spacing="xl">
              <Stack gap="md">
                <Skeleton height={20} width={180} />
                <Skeleton height={18} width="85%" />
                <Skeleton height={18} width="95%" />
              </Stack>
              <Stack gap="md">
                <Skeleton height={58} />
                <Skeleton height={36} width={150} />
              </Stack>
            </SimpleGrid>
          ) : loadError ? (
            <Text role="alert" c="red">
              Failed to load pricing settings: {loadError}
            </Text>
          ) : pricing ? (
            <SimpleGrid cols={{ base: 1, md: 2 }} spacing="xl">
              <Stack gap="xs">
                <Title order={2} fz="md">
                  Appraisal price floor
                </Title>
                <Text size="sm" c="dimmed">
                  Minimum suggested price for future appraisals. Defaults to
                  NZ$0.25.
                </Text>
                <Text size="sm" c="dimmed">
                  Cards with a market value below NZ$0.25 are still discarded.
                  Saving this setting does not reprice existing inventory.
                </Text>
              </Stack>
              <Stack gap="md" align="stretch">
                <TextInput
                  label="Price floor (NZD)"
                  value={priceFloor}
                  onChange={(event) => setPriceFloor(event.currentTarget.value)}
                  inputMode="decimal"
                  type="text"
                  autoComplete="off"
                />
                <Group justify="flex-end">
                  <Button
                    onClick={handleSave}
                    loading={saving}
                    disabled={saving || priceFloor.trim() === '' || unchanged}
                  >
                    Save price floor
                  </Button>
                </Group>
              </Stack>
            </SimpleGrid>
          ) : null}
        </Paper>
      </Stack>
    </AppShellLayout>
  );
}
