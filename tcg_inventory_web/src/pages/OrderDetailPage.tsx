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
import type { OrderDetail, OrderNeighborCard, OrderUnit } from '../api/client';
import { ListPriceBadge } from '../components/ListPriceBadge';

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

function neighborDescription(card: OrderNeighborCard): string {
  const parts = [
    card.name,
    `${card.set_code.toUpperCase()} #${card.collector_number}`,
    card.condition,
  ];
  if (card.finish !== 'normal') {
    parts.push(card.finish);
  }
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

  // reserved cards are still boxed before the pull, so positions and neighbors
  // only make sense until the order is fulfilled or voided
  const showPullContext =
    order?.state === 'awaiting_payment' || order?.state === 'to_pick';

  const handleConfirm = async () => {
    if (!orderId) {
      return;
    }
    setConfirming(true);
    try {
      await apiClient.confirmOrder(orderId);
      setOrder(await apiClient.getOrder(orderId));
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
              <Text size="sm" c="dimmed">
                {order.delivery_mode} · ${order.total_price}
              </Text>
              {order.items_total_price != null && (
                <Group gap="sm" align="center">
                  <Text size="sm" c="dimmed">
                    Offered ${order.items_total_price}
                    {order.listed_total_price != null &&
                      ` · Listed $${order.listed_total_price}`}
                  </Text>
                  {order.listed_total_price != null && (
                    <ListPriceBadge
                      offered={order.items_total_price}
                      listed={order.listed_total_price}
                    />
                  )}
                </Group>
              )}
            </Stack>
            <Stack gap="sm" maw={480}>
              <Title order={3}>
                {order.state === 'to_pick' ? 'Pull sheet' : 'Cards'}
              </Title>
              {order.units.map((unit) => (
                <Paper key={unit.sequence_number} withBorder p="md" radius="md">
                  <Stack gap={6}>
                    <Group
                      justify="space-between"
                      align="flex-start"
                      wrap="nowrap"
                      gap="md"
                    >
                      <Group gap="xs" align="baseline" wrap="nowrap">
                        <Text fz={28} fw={700} style={{ whiteSpace: 'nowrap' }}>
                          {showPullContext
                            ? unit.current_location
                            : unit.location}
                        </Text>
                        {showPullContext &&
                          unit.current_location !== unit.location && (
                            <Text
                              size="sm"
                              c="dimmed"
                              td="line-through"
                              style={{ whiteSpace: 'nowrap' }}
                            >
                              {unit.location}
                            </Text>
                          )}
                      </Group>
                      {unit.price != null && (
                        <Text fw={600} style={{ whiteSpace: 'nowrap' }}>
                          ${unit.price}
                        </Text>
                      )}
                    </Group>
                    <Stack gap={2}>
                      <Text fw={500}>{unit.name}</Text>
                      <Text size="sm" c="dimmed">
                        {unitDescription(unit)}
                      </Text>
                    </Stack>
                    {showPullContext &&
                      (unit.previous_card != null ||
                        unit.next_card != null) && (
                        <Stack gap={2}>
                          {unit.previous_card != null && (
                            <Text size="xs" c="dimmed">
                              Prev · {neighborDescription(unit.previous_card)}
                            </Text>
                          )}
                          {unit.next_card != null && (
                            <Text size="xs" c="dimmed">
                              Next · {neighborDescription(unit.next_card)}
                            </Text>
                          )}
                        </Stack>
                      )}
                  </Stack>
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
