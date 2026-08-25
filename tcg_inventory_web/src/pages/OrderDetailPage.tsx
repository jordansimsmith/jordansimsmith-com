import { useEffect, useState } from 'react';
import {
  Button,
  Group,
  Paper,
  Skeleton,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useNavigate, useParams } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { OrderStateBadge } from '../components/OrderStateBadge';
import { ConfirmPullModal } from '../components/ConfirmPullModal';
import { apiClient } from '../api/client';
import type { OrderDetail, OrderLine, OrderUnit } from '../api/client';
import { ListPriceBadge } from '../components/ListPriceBadge';
import { listedLineTotal } from '../domain/listPrice';

function unitDescription(unit: OrderUnit): string {
  const parts = [
    `${unit.set_code.toUpperCase()} #${unit.collector_number}`,
    unit.condition,
  ];
  if (unit.finish !== 'normal') {
    parts.push(unit.finish);
  }
  return parts.join(' · ');
}

function lineDescription(line: OrderLine): string {
  const parts = [
    `${line.set_code.toUpperCase()} #${line.collector_number}`,
    line.condition,
  ];
  if (line.finish !== 'normal') {
    parts.push(line.finish);
  }
  parts.push(`×${line.quantity}`);
  return parts.join(' · ');
}

export function OrderDetailPage() {
  const { orderId } = useParams<{ orderId: string }>();
  const navigate = useNavigate();
  const [order, setOrder] = useState<OrderDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [confirming, setConfirming] = useState(false);

  useEffect(() => {
    if (!orderId) {
      return;
    }
    let cancelled = false;
    const fetchOrder = async () => {
      try {
        const response = await apiClient.getOrder(orderId);
        if (!cancelled) {
          setOrder(response);
        }
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : 'Failed to load order');
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    fetchOrder();
    return () => {
      cancelled = true;
    };
  }, [orderId]);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || confirmOpen) {
        return;
      }
      const target = event.target;
      if (
        target instanceof HTMLInputElement ||
        target instanceof HTMLTextAreaElement
      ) {
        return;
      }
      navigate('/orders');
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [confirmOpen, navigate]);

  const handleConfirm = async () => {
    if (!orderId) {
      return;
    }
    setConfirming(true);
    try {
      const response = await apiClient.confirmOrder(orderId);
      setOrder(response);
      setConfirmOpen(false);
      notifications.show({
        title: 'Order fulfilled',
        message: 'All units are marked sold.',
        color: 'green',
      });
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Failed to confirm pull';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setConfirming(false);
    }
  };

  return (
    <AppShellLayout>
      <Stack gap="md">
        {loading && (
          <Stack gap="sm">
            <Skeleton height={32} width={280} />
            <Skeleton height={20} width={200} />
            <Skeleton height={20} width={240} />
          </Stack>
        )}
        {!loading && error && (
          <Stack align="flex-start" gap="md">
            <Text c="red">{error}</Text>
            <Button variant="default" onClick={() => navigate('/orders')}>
              Back to orders
            </Button>
          </Stack>
        )}
        {!loading && !error && order && (
          <>
            <Stack gap="xs">
              <Title order={2}>Order {order.order_id}</Title>
              <Group gap="sm">
                <OrderStateBadge state={order.state} />
                <Text size="sm" c="dimmed">
                  Accepted {new Date(order.accepted_at * 1000).toLocaleString()}
                </Text>
              </Group>
              <Group gap="sm" align="center">
                <Text size="sm" c="dimmed">
                  {order.delivery_mode} · ${order.total_price}
                </Text>
                {order.items_total_price != null &&
                  order.listed_total_price != null && (
                    <ListPriceBadge
                      offered={order.items_total_price}
                      listed={order.listed_total_price}
                    />
                  )}
              </Group>
            </Stack>
            {order.lines.length > 0 && (
              <Stack gap="sm" maw={480}>
                <Title order={3}>Offer</Title>
                {order.lines.map((line, index) => {
                  const listedTotal =
                    line.listed_price != null
                      ? listedLineTotal(line.listed_price, line.quantity)
                      : null;
                  return (
                    <Paper
                      key={`${line.name}-${line.set_code}-${index}`}
                      withBorder
                      p="md"
                      radius="md"
                    >
                      <Group
                        justify="space-between"
                        align="flex-start"
                        wrap="nowrap"
                        gap="md"
                      >
                        <Stack gap={2} style={{ minWidth: 0 }}>
                          <Text fw={500}>{line.name}</Text>
                          <Text size="sm" c="dimmed">
                            {lineDescription(line)}
                          </Text>
                        </Stack>
                        <Stack gap={4} align="flex-end">
                          {line.price != null && (
                            <Text fw={500}>${line.price}</Text>
                          )}
                          {listedTotal != null && (
                            <Text size="sm" c="dimmed">
                              listed ${listedTotal}
                            </Text>
                          )}
                          {line.price != null && listedTotal != null && (
                            <ListPriceBadge
                              offered={line.price}
                              listed={listedTotal}
                            />
                          )}
                        </Stack>
                      </Group>
                    </Paper>
                  );
                })}
              </Stack>
            )}
            <Stack gap="sm" maw={480}>
              <Title order={3}>
                {order.state === 'to_pick' ? 'Pull sheet' : 'Cards'}
              </Title>
              {order.units.map((unit) => (
                <Paper key={unit.sequence_number} withBorder p="md" radius="md">
                  <Group
                    justify="space-between"
                    align="center"
                    wrap="nowrap"
                    gap="md"
                  >
                    <Text fz={28} fw={700} style={{ whiteSpace: 'nowrap' }}>
                      {unit.location}
                    </Text>
                    <Stack gap={2} align="flex-end" style={{ minWidth: 0 }}>
                      <Text fw={500} ta="right">
                        {unit.name}
                      </Text>
                      <Text size="sm" c="dimmed" ta="right">
                        {unitDescription(unit)}
                      </Text>
                    </Stack>
                  </Group>
                </Paper>
              ))}
              {order.state === 'to_pick' && (
                <Button size="lg" onClick={() => setConfirmOpen(true)}>
                  Confirm pull
                </Button>
              )}
            </Stack>
            <ConfirmPullModal
              opened={confirmOpen}
              unitCount={order.unit_count}
              loading={confirming}
              onCancel={() => setConfirmOpen(false)}
              onConfirm={handleConfirm}
            />
          </>
        )}
      </Stack>
    </AppShellLayout>
  );
}
