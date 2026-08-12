import { useEffect, useRef, useState } from 'react';
import { Skeleton, Stack, Text, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useNavigate } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { OrderTable } from '../components/OrderTable';
import { apiClient } from '../api/client';
import type { OrderSummary } from '../api/client';
import { useListNavigation } from '../hooks/use-list-navigation';

export function OrdersPage() {
  const navigate = useNavigate();
  const [orders, setOrders] = useState<OrderSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // this page has no search input; the ref keeps the navigation hook inert on "/"
  const searchInputRef = useRef<HTMLInputElement>(null);

  const openOrder = (order: OrderSummary) => {
    navigate(`/orders/${encodeURIComponent(order.order_id)}`);
  };

  const { selectedIndex } = useListNavigation({
    itemCount: orders.length,
    onOpen: (index) => {
      const order = orders[index];
      if (order) {
        openOrder(order);
      }
    },
    searchInputRef,
  });

  useEffect(() => {
    let cancelled = false;
    const fetchOrders = async () => {
      try {
        const response = await apiClient.findOrders();
        if (!cancelled) {
          setOrders(response.orders);
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

  return (
    <AppShellLayout>
      <Stack gap="md">
        <Title order={2}>Orders</Title>
        {loading && (
          <Stack gap="xs">
            {[1, 2, 3].map((row) => (
              <Skeleton key={row} height={28} />
            ))}
          </Stack>
        )}
        {!loading && error && (
          <Text c="red" ta="center">
            {error}
          </Text>
        )}
        {!loading && !error && orders.length === 0 && (
          <Text c="dimmed">No orders yet.</Text>
        )}
        {!loading && !error && orders.length > 0 && (
          <OrderTable
            orders={orders}
            selectedIndex={selectedIndex}
            onOpen={openOrder}
          />
        )}
      </Stack>
    </AppShellLayout>
  );
}
