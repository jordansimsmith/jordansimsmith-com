import { useEffect, useState } from 'react';
import {
  Box,
  Button,
  Group,
  Image,
  Paper,
  Skeleton,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import { IconExternalLink } from '@tabler/icons-react';
import { notifications } from '@mantine/notifications';
import { useNavigate, useParams } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { OrderStateBadge } from '../components/OrderStateBadge';
import { ConfirmPullModal } from '../components/ConfirmPullModal';
import { apiClient } from '../api/client';
import type {
  BuyerAddress,
  OrderDetail,
  OrderNeighborCard,
  OrderUnit,
} from '../api/client';
import { ListPriceBadge } from '../components/ListPriceBadge';
import { PageHeader } from '../components/PageHeader';
import { formatDeliveryMode } from '../domain/deliveryMode';
import classes from './OrderDetailPage.module.css';

const CARD_IMAGE_FALLBACK =
  "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='146' height='204' viewBox='0 0 146 204'%3E%3Crect width='146' height='204' rx='8' fill='%23e9ecef' stroke='%23ced4da'/%3E%3C/svg%3E";
const TRADEME_COURIER_URL =
  'https://www.trademe.co.nz/a/marketplace/book-courier/select';

function cardImageUrl(scryfallId: string): string {
  return `https://api.scryfall.com/cards/${encodeURIComponent(scryfallId)}?format=image&version=small`;
}

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

function addressLines(address: BuyerAddress): string[] {
  const locality = [
    [address.suburb, address.city].filter(Boolean).join(', '),
    address.post_code,
  ]
    .filter(Boolean)
    .join(' ');
  return [address.line1, address.line2, locality, address.country].filter(
    (line): line is string => line != null && line !== '',
  );
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
            <PageHeader
              title={`Order ${order.order_id}`}
              description={`Accepted ${new Date(order.accepted_at * 1000).toLocaleString()}`}
              actions={
                <Button variant="subtle" onClick={() => navigate('/orders')}>
                  Back to orders
                </Button>
              }
            />
            <Box className={classes.detailGrid}>
              <Stack gap="md" className={classes.detailSupport}>
                <Paper
                  component="section"
                  aria-label="Order summary"
                  withBorder
                  p="md"
                  radius="md"
                >
                  <Stack gap="sm">
                    <Group justify="space-between" gap="sm">
                      <Title order={3} fz="md">
                        Order summary
                      </Title>
                      <OrderStateBadge state={order.state} />
                    </Group>
                    <Stack gap={4} className={classes.numeric}>
                      <Text size="sm" fw={600}>
                        Total ${order.total_price}
                      </Text>
                      {order.items_total_price != null && (
                        <Group gap="xs" align="center">
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
                  </Stack>
                </Paper>
                <Paper
                  component="section"
                  aria-label="Delivery"
                  withBorder
                  p="md"
                  radius="md"
                >
                  <Stack gap="sm">
                    <Title order={3} fz="md">
                      Delivery
                    </Title>
                    <Stack gap={4}>
                      {order.buyer_name != null && (
                        <Text fw={500}>{order.buyer_name}</Text>
                      )}
                      {order.buyer_address != null &&
                        addressLines(order.buyer_address).map((line) => (
                          <Text key={line} size="sm">
                            {line}
                          </Text>
                        ))}
                      <Text size="sm" c="dimmed">
                        {order.postage_option ??
                          formatDeliveryMode(order.delivery_mode)}
                      </Text>
                    </Stack>
                  </Stack>
                </Paper>
                <Paper
                  component="section"
                  aria-label="Order actions"
                  withBorder
                  p="md"
                  radius="md"
                >
                  <Stack gap="sm">
                    <Title order={3} fz="md">
                      Order actions
                    </Title>
                    <Stack gap="xs">
                      <Button
                        component="a"
                        href={`https://www.fetchtcg.com/profile/sales/${encodeURIComponent(order.order_id)}`}
                        target="_blank"
                        rel="noopener noreferrer"
                        variant="default"
                        fullWidth
                        justify="space-between"
                        rightSection={<IconExternalLink size={16} />}
                      >
                        View in FetchTCG
                      </Button>
                      {order.state === 'fulfilled' && (
                        <Button
                          component="a"
                          href={TRADEME_COURIER_URL}
                          target="_blank"
                          rel="noopener noreferrer"
                          variant="default"
                          fullWidth
                          justify="space-between"
                          rightSection={<IconExternalLink size={16} />}
                        >
                          Book a courier
                        </Button>
                      )}
                    </Stack>
                  </Stack>
                </Paper>
              </Stack>
              <Paper
                component="section"
                aria-label={order.state === 'to_pick' ? 'Pull sheet' : 'Cards'}
                className={classes.pullSurface}
                withBorder
                radius="md"
              >
                <Box p="md" className={classes.pullHeader}>
                  <Group justify="space-between" align="baseline" gap="sm">
                    <Title order={3} fz="md">
                      {order.state === 'to_pick' ? 'Pull sheet' : 'Cards'}
                    </Title>
                    <Text size="xs" c="dimmed" className={classes.numeric}>
                      {order.unit_count}{' '}
                      {order.unit_count === 1 ? 'card' : 'cards'}
                      {order.state === 'to_pick' && ' · location order'}
                    </Text>
                  </Group>
                </Box>
                {order.units.map((unit) => (
                  <Box key={unit.sequence_number} className={classes.pullRow}>
                    <Box className={classes.pullImage}>
                      <Image
                        src={cardImageUrl(unit.scryfall_id)}
                        fallbackSrc={CARD_IMAGE_FALLBACK}
                        alt=""
                        fit="contain"
                        w="100%"
                        h="100%"
                        loading="lazy"
                        decoding="async"
                        fetchPriority="low"
                        radius="sm"
                      />
                    </Box>
                    <Group
                      className={classes.pullPosition}
                      align="baseline"
                      wrap="nowrap"
                      gap="xs"
                    >
                      <Text fz={26} fw={700} className={classes.location}>
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
                            className={classes.location}
                          >
                            {unit.location}
                          </Text>
                        )}
                    </Group>
                    <Stack gap={2} className={classes.pullCard}>
                      <Text fw={500}>{unit.name}</Text>
                      <Text size="sm" c="dimmed">
                        {unitDescription(unit)}
                      </Text>
                    </Stack>
                    {unit.price != null && (
                      <Text
                        fw={600}
                        className={`${classes.pullPrice} ${classes.location}`}
                      >
                        ${unit.price}
                      </Text>
                    )}
                    {showPullContext &&
                      (unit.previous_card != null ||
                        unit.next_card != null) && (
                        <Stack gap={2} className={classes.neighbors}>
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
                  </Box>
                ))}
                {order.state === 'to_pick' && (
                  <Box p="md" className={classes.pullFooter}>
                    <Button onClick={() => setConfirmOpen(true)}>
                      Confirm pull
                    </Button>
                  </Box>
                )}
              </Paper>
            </Box>
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
