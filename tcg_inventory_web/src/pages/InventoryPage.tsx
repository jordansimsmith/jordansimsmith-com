import { useEffect, useRef, useState } from 'react';
import {
  Button,
  Group,
  Skeleton,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useNavigate } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { PublishWidget } from '../components/PublishWidget';
import { SkuTable } from '../components/SkuTable';
import { apiClient } from '../api/client';
import type { SkuSummary } from '../api/client';
import { useListNavigation } from '../hooks/use-list-navigation';

export function InventoryPage() {
  const navigate = useNavigate();
  const [skus, setSkus] = useState<SkuSummary[]>([]);
  const [nextContinuation, setNextContinuation] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const requestIdRef = useRef(0);

  const openSku = (sku: SkuSummary) => {
    navigate(`/inventory/${encodeURIComponent(sku.sku_id)}`);
  };

  const { selectedIndex, setSelectedIndex } = useListNavigation({
    itemCount: skus.length,
    onOpen: (index) => {
      const sku = skus[index];
      if (sku) {
        openSku(sku);
      }
    },
    searchInputRef,
  });

  useEffect(() => {
    const requestId = ++requestIdRef.current;
    const fetchSkus = async () => {
      try {
        const response = await apiClient.findSkus(
          search ? { search } : undefined,
        );
        if (requestId !== requestIdRef.current) {
          return;
        }
        setSkus(response.skus);
        setNextContinuation(response.next_continuation);
        setSelectedIndex(0);
        setError(null);
      } catch (e) {
        if (requestId !== requestIdRef.current) {
          return;
        }
        const message = e instanceof Error ? e.message : 'Failed to load SKUs';
        setError(message);
        notifications.show({ title: 'Error', message, color: 'red' });
      } finally {
        if (requestId === requestIdRef.current) {
          setLoading(false);
        }
      }
    };

    fetchSkus();
  }, [search, setSelectedIndex]);

  const handleLoadMore = async () => {
    if (!nextContinuation) {
      return;
    }
    setLoadingMore(true);
    try {
      const response = await apiClient.findSkus({
        search: search || undefined,
        continuation: nextContinuation,
      });
      setSkus((previous) => [...previous, ...response.skus]);
      setNextContinuation(response.next_continuation);
    } catch (e) {
      const message =
        e instanceof Error ? e.message : 'Failed to load more SKUs';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setLoadingMore(false);
    }
  };

  return (
    <AppShellLayout>
      <Stack gap="md">
        <Group justify="space-between" align="flex-start">
          <Title order={2}>Inventory</Title>
          <PublishWidget />
        </Group>
        <TextInput
          ref={searchInputRef}
          value={search}
          onChange={(event) => setSearch(event.currentTarget.value)}
          placeholder="Search by name, / to focus"
          aria-label="Search SKUs"
          maw={400}
        />
        {loading && (
          <Stack gap="xs">
            {[1, 2, 3, 4, 5].map((row) => (
              <Skeleton key={row} height={28} />
            ))}
          </Stack>
        )}
        {!loading && error && (
          <Text c="red" ta="center">
            {error}
          </Text>
        )}
        {!loading && !error && skus.length === 0 && (
          <Text c="dimmed">No SKUs found.</Text>
        )}
        {!loading && !error && skus.length > 0 && (
          <>
            <SkuTable
              skus={skus}
              selectedIndex={selectedIndex}
              onOpen={openSku}
            />
            {nextContinuation && (
              <Button
                variant="default"
                onClick={handleLoadMore}
                loading={loadingMore}
                w="fit-content"
              >
                Load more
              </Button>
            )}
          </>
        )}
      </Stack>
    </AppShellLayout>
  );
}
