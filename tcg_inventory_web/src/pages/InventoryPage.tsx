import { useEffect, useRef, useState } from 'react';
import { Button, Group, Stack, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useNavigate } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';
import {
  CollectionLoadingState,
  CollectionMessage,
  CollectionSurface,
} from '../components/CollectionSurface';
import { PageHeader } from '../components/PageHeader';
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
  const [debouncedSearch, setDebouncedSearch] = useState('');
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
    const timer = setTimeout(() => setDebouncedSearch(search), 300);
    return () => clearTimeout(timer);
  }, [search]);

  useEffect(() => {
    const requestId = ++requestIdRef.current;
    const fetchSkus = async () => {
      try {
        const response = await apiClient.findSkus(
          debouncedSearch ? { search: debouncedSearch } : undefined,
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
  }, [debouncedSearch, setSelectedIndex]);

  const handleLoadMore = async () => {
    if (!nextContinuation) {
      return;
    }
    setLoadingMore(true);
    try {
      const response = await apiClient.findSkus({
        search: debouncedSearch || undefined,
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
      <Stack gap="lg">
        <PageHeader
          title="Inventory"
          description="Find stock by card name and open a printing to view its units and locations."
          actions={<PublishWidget />}
        />
        <CollectionSurface
          ariaLabel="Inventory"
          toolbar={
            <TextInput
              ref={searchInputRef}
              value={search}
              onChange={(event) => setSearch(event.currentTarget.value)}
              label="Search inventory"
              placeholder="Card name, / to focus"
              aria-label="Search SKUs"
              maw={400}
            />
          }
          footer={
            nextContinuation ? (
              <Group justify="flex-start">
                <Button
                  variant="default"
                  onClick={handleLoadMore}
                  loading={loadingMore}
                >
                  Load more
                </Button>
              </Group>
            ) : undefined
          }
        >
          {loading && <CollectionLoadingState />}
          {!loading && error && (
            <CollectionMessage
              title="Inventory could not be loaded"
              description={error}
              tone="error"
            />
          )}
          {!loading && !error && skus.length === 0 && (
            <CollectionMessage
              title="No SKUs found."
              description="Try a different card name."
            />
          )}
          {!loading && !error && skus.length > 0 && (
            <SkuTable
              skus={skus}
              selectedIndex={selectedIndex}
              onOpen={openSku}
            />
          )}
        </CollectionSurface>
      </Stack>
    </AppShellLayout>
  );
}
