import { useEffect, useState } from 'react';
import {
  Badge,
  Button,
  Group,
  Image,
  Skeleton,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useNavigate, useParams } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { UnitTable } from '../components/UnitTable';
import { RemoveUnitModal } from '../components/RemoveUnitModal';
import { EditConditionModal } from '../components/EditConditionModal';
import { apiClient } from '../api/client';
import type { Condition, SkuDetail, SkuUnit } from '../api/client';

const CARD_IMAGE_FALLBACK =
  "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='488' height='680'%3E%3Crect width='100%25' height='100%25' fill='%23e9ecef' rx='24'/%3E%3C/svg%3E";

// scryfall "normal" images are 488x680; reserving the box prevents layout shift while loading
const CARD_IMAGE_WIDTH = 260;
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
      <Stack gap="md">
        {loading && (
          <Group align="flex-start" gap="xl">
            <Skeleton
              width={CARD_IMAGE_WIDTH}
              style={{ aspectRatio: CARD_IMAGE_ASPECT_RATIO }}
              radius="md"
            />
            <Stack gap="sm" flex={1}>
              <Skeleton height={32} width={280} />
              <Skeleton height={20} width={200} />
              <Skeleton height={20} width={240} />
            </Stack>
          </Group>
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
            <Group align="flex-start" gap="xl">
              <Image
                src={`https://api.scryfall.com/cards/${sku.scryfall_id}?format=image&version=normal`}
                fallbackSrc={CARD_IMAGE_FALLBACK}
                alt={sku.name}
                w={CARD_IMAGE_WIDTH}
                style={{ aspectRatio: CARD_IMAGE_ASPECT_RATIO }}
                radius="md"
              />
              <Stack gap="xs" flex={1} miw={260}>
                <Title order={2}>{sku.name}</Title>
                <Text c="dimmed">
                  {sku.set_name} ({sku.set_code.toUpperCase()}) · #
                  {sku.collector_number}
                </Text>
                <Group gap="xs">
                  <Badge variant="light">{sku.finish}</Badge>
                  <Badge variant="light" color="grape">
                    {sku.condition}
                  </Badge>
                </Group>
                <Group gap="lg" mt="xs">
                  <Text size="sm">In stock: {sku.in_stock_count}</Text>
                  <Text size="sm">Reserved: {sku.reserved_count}</Text>
                  <Text size="sm">Sold: {sku.sold_count}</Text>
                </Group>
              </Stack>
            </Group>
            <UnitTable
              units={sku.units}
              onRemove={setRemovingUnit}
              onEditCondition={setEditingUnit}
            />
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
