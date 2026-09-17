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
import { notifications } from '@mantine/notifications';
import { useNavigate, useParams } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { UnitTable } from '../components/UnitTable';
import { PageHeader } from '../components/PageHeader';
import { RemoveUnitModal } from '../components/RemoveUnitModal';
import { EditConditionModal } from '../components/EditConditionModal';
import { apiClient } from '../api/client';
import type { Condition, SkuDetail, SkuUnit } from '../api/client';
import classes from './SkuDetailPage.module.css';

const CARD_IMAGE_FALLBACK =
  "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='488' height='680'%3E%3Crect width='100%25' height='100%25' fill='%23e9ecef' rx='24'/%3E%3C/svg%3E";

const CARD_IMAGE_ASPECT_RATIO = '488 / 680';

export function SkuDetailPage() {
  const { skuId } = useParams<{ skuId: string }>();
  const navigate = useNavigate();
  const [sku, setSku] = useState<SkuDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [removingUnit, setRemovingUnit] = useState<SkuUnit | null>(null);
  const [editingUnit, setEditingUnit] = useState<SkuUnit | null>(null);
  const [actionLoading, setActionLoading] = useState(false);

  useEffect(() => {
    if (!skuId) {
      return;
    }
    let cancelled = false;
    const fetchSku = async () => {
      setLoading(true);
      setError(null);
      try {
        const response = await apiClient.getSku(skuId);
        if (!cancelled) {
          setSku(response);
        }
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : 'Failed to load SKU');
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    fetchSku();
    return () => {
      cancelled = true;
    };
  }, [skuId]);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || removingUnit || editingUnit) {
        return;
      }
      const target = event.target;
      if (
        target instanceof HTMLInputElement ||
        target instanceof HTMLTextAreaElement
      ) {
        return;
      }
      navigate('/inventory');
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [editingUnit, navigate, removingUnit]);

  const handleRemoveConfirm = async (reason: string) => {
    if (!sku || !removingUnit) {
      return;
    }
    setActionLoading(true);
    try {
      await apiClient.deleteUnit(
        sku.sku_id,
        removingUnit.sequence_number,
        reason || undefined,
      );
      const updated = await apiClient.getSku(sku.sku_id);
      setSku(updated);
      setRemovingUnit(null);
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Failed to remove unit';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setActionLoading(false);
    }
  };

  const handleEditConfirm = async (condition: Condition) => {
    if (!sku || !editingUnit) {
      return;
    }
    setActionLoading(true);
    try {
      const response = await apiClient.updateUnit(
        sku.sku_id,
        editingUnit.sequence_number,
        condition,
      );
      setEditingUnit(null);
      navigate(`/inventory/${encodeURIComponent(response.sku_id)}`);
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Failed to update unit';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setActionLoading(false);
    }
  };

  return (
    <AppShellLayout>
      <Stack gap="lg">
        {loading && (
          <>
            <Stack gap="xs">
              <Skeleton height={29} width={220} />
              <Skeleton height={18} width={280} />
            </Stack>
            <Paper withBorder radius="md" p="md">
              <Box className={classes.overview}>
                <Skeleton className={classes.cardImageFrame} />
                <Stack gap="md" flex={1}>
                  <Skeleton height={20} width={160} />
                  <Skeleton height={44} />
                  <Skeleton height={56} />
                </Stack>
              </Box>
            </Paper>
            <Paper withBorder radius="md" p="md">
              <Skeleton height={24} width={100} mb="md" />
              <Stack gap="xs">
                <Skeleton height={36} />
                <Skeleton height={36} />
                <Skeleton height={36} />
              </Stack>
            </Paper>
          </>
        )}
        {!loading && error && (
          <Stack align="flex-start" gap="md">
            <Text c="red">{error}</Text>
            <Button variant="default" onClick={() => navigate('/inventory')}>
              Back to inventory
            </Button>
          </Stack>
        )}
        {!loading && !error && sku && (
          <>
            <PageHeader
              title={sku.name}
              description={`${sku.set_name} (${sku.set_code.toUpperCase()}) · #${sku.collector_number}`}
              actions={
                <Button variant="subtle" onClick={() => navigate('/inventory')}>
                  Back to inventory
                </Button>
              }
            />
            <Paper
              component="section"
              aria-label="Card overview"
              withBorder
              radius="md"
              p="md"
            >
              <Box className={classes.overview}>
                <Box className={classes.cardImageFrame}>
                  <Image
                    src={`https://api.scryfall.com/cards/${sku.scryfall_id}?format=image&version=normal`}
                    fallbackSrc={CARD_IMAGE_FALLBACK}
                    alt={sku.name}
                    w="100%"
                    h="100%"
                    fit="contain"
                    style={{ aspectRatio: CARD_IMAGE_ASPECT_RATIO }}
                    radius="sm"
                  />
                </Box>
                <Stack gap="md" className={classes.overviewDetails}>
                  <div className={classes.attributes}>
                    <div>
                      <Text size="xs" c="dimmed" fw={600}>
                        Finish
                      </Text>
                      <Text size="sm" tt="capitalize">
                        {sku.finish}
                      </Text>
                    </div>
                    <div>
                      <Text size="xs" c="dimmed" fw={600}>
                        Condition
                      </Text>
                      <Text size="sm">{sku.condition}</Text>
                    </div>
                    <div>
                      <Text size="xs" c="dimmed" fw={600}>
                        Listed price
                      </Text>
                      <Text size="sm" className={classes.numeric}>
                        {sku.last_published_price != null
                          ? `$${sku.last_published_price}`
                          : 'Not listed'}
                      </Text>
                    </div>
                  </div>
                  <div className={classes.stockSummary}>
                    <Text size="sm">In stock: {sku.in_stock_count}</Text>
                    <Text size="sm">Reserved: {sku.reserved_count}</Text>
                    <Text size="sm">Sold: {sku.sold_count}</Text>
                  </div>
                </Stack>
              </Box>
            </Paper>
            <Paper
              component="section"
              aria-label="Units"
              withBorder
              radius="md"
            >
              <Group
                justify="space-between"
                gap="sm"
                px="md"
                py="sm"
                className={classes.unitsHeader}
              >
                <Title order={3} fz="md">
                  Units
                </Title>
                <Text size="sm" c="dimmed" className={classes.numeric}>
                  {sku.units.length} {sku.units.length === 1 ? 'unit' : 'units'}
                </Text>
              </Group>
              <Box style={{ overflowX: 'auto' }}>
                <UnitTable
                  units={sku.units}
                  onRemove={setRemovingUnit}
                  onEditCondition={setEditingUnit}
                />
              </Box>
            </Paper>
            <RemoveUnitModal
              unit={removingUnit}
              loading={actionLoading}
              onCancel={() => setRemovingUnit(null)}
              onConfirm={handleRemoveConfirm}
            />
            <EditConditionModal
              unit={editingUnit}
              currentCondition={sku.condition}
              loading={actionLoading}
              onCancel={() => setEditingUnit(null)}
              onConfirm={handleEditConfirm}
            />
          </>
        )}
      </Stack>
    </AppShellLayout>
  );
}
