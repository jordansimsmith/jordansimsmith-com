import { useEffect, useState } from 'react';
import { Button, Group, Stack } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useNavigate } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';
import {
  CollectionLoadingState,
  CollectionMessage,
  CollectionSurface,
} from '../components/CollectionSurface';
import { OrderTable } from '../components/OrderTable';
import { PageHeader } from '../components/PageHeader';
import { apiClient } from '../api/client';
import type { OrderSummary } from '../api/client';

export function OrdersPage() {
  const navigate = useNavigate();
  const [orders, setOrders] = useState<OrderSummary[]>([]);
  const [nextContinuation, setNextContinuation] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const openOrder = (order: OrderSummary) => {
    navigate(`/orders/${encodeURIComponent(order.order_id)}`);
  };

  useEffect(() => {
    let cancelled = false;
    const fetchOrders = async () => {
      try {
        const response = await apiClient.findOrders();
        if (!cancelled) {
          setOrders(response.orders);
          setNextContinuation(response.next_continuation);
        }
      } catch (e) {
        if (!cancelled) {
          const message =
            e instanceof Error ? e.message : 'Failed to load orders';
          setError(message);
          notifications.show({ title: 'Error', message, color: 'red' });
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    fetchOrders();
    return () => {
      cancelled = true;
    };
  }, []);

  const handleLoadMore = async () => {
    if (!nextContinuation) {
      return;
    }
    setLoadingMore(true);
    try {
      const response = await apiClient.findOrders({
        continuation: nextContinuation,
      });
      setOrders((previous) => [...previous, ...response.orders]);
      setNextContinuation(response.next_continuation);
    } catch (e) {
      const message =
        e instanceof Error ? e.message : 'Failed to load more orders';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setLoadingMore(false);
    }
  };

  return (
    <AppShellLayout>
      <Stack gap="lg">
        <PageHeader
          title="Orders"
          description="Review accepted orders and open pull sheets for fulfillment."
        />
        <CollectionSurface
          ariaLabel="Orders"
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
          {loading && <CollectionLoadingState rows={3} />}
          {!loading && error && (
            <CollectionMessage
              title="Orders could not be loaded"
              description={error}
              tone="error"
            />
          )}
          {!loading && !error && orders.length === 0 && (
            <CollectionMessage title="No orders yet." />
          )}
          {!loading && !error && orders.length > 0 && (
            <OrderTable orders={orders} onOpen={openOrder} />
          )}
        </CollectionSurface>
      </Stack>
    </AppShellLayout>
  );
}
