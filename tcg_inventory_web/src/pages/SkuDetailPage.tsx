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
import { useGames } from '../GamesProvider';
import type { Condition, SkuDetail, SkuUnit } from '../api/client';
import classes from './SkuDetailPage.module.css';

const CARD_IMAGE_FALLBACK =
  "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='488' height='680'%3E%3Crect width='100%25' height='100%25' fill='%23e9ecef' rx='24'/%3E%3C/svg%3E";

const CARD_IMAGE_ASPECT_RATIO = '488 / 680';
const MAX_PHOTOS_PER_REQUEST = 100;

async function findPhotoUrls(photoIds: string[]): Promise<Map<string, string>> {
  const distinctPhotoIds = [...new Set(photoIds)];
  const urls = new Map<string, string>();

  for (
    let offset = 0;
    offset < distinctPhotoIds.length;
    offset += MAX_PHOTOS_PER_REQUEST
  ) {
    const response = await apiClient.findPhotos({
      photo_ids: distinctPhotoIds.slice(
        offset,
        offset + MAX_PHOTOS_PER_REQUEST,
      ),
    });
    for (const photo of response.photos) {
      urls.set(photo.photo_id, photo.url);
    }
  }

  return urls;
}

async function loadUnitsWithPhotos(skuId: string) {
  const units: SkuUnit[] = [];
  let continuation: string | undefined;
  do {
    const page = await apiClient.findUnits({
      sku_id: skuId,
      limit: 100,
      continuation,
    });
    units.push(...page.units);
    continuation = page.next_continuation ?? undefined;
  } while (continuation !== undefined);

  const photoIds = units.flatMap((unit) => unit.photos);
  const photoUrls = await findPhotoUrls(photoIds);
  return { units, photoUrls };
}

export function SkuDetailPage() {
  const { getFinish } = useGames();
  const { skuId } = useParams<{ skuId: string }>();
  const navigate = useNavigate();
  const [sku, setSku] = useState<SkuDetail | null>(null);
  const [units, setUnits] = useState<SkuUnit[]>([]);
  const [photoUrls, setPhotoUrls] = useState<Map<string, string>>(new Map());
  const [cardImage, setCardImage] = useState<string | null>(null);
  const [cardImageLoading, setCardImageLoading] = useState(false);
  const [cardImageError, setCardImageError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [unitsLoading, setUnitsLoading] = useState(true);
  const [unitsError, setUnitsError] = useState<string | null>(null);
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
      setSku(null);
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
    if (!skuId) {
      return;
    }
    let cancelled = false;
    const fetchUnits = async () => {
      setUnitsLoading(true);
      setUnitsError(null);
      setUnits([]);
      setPhotoUrls(new Map());
      try {
        const response = await loadUnitsWithPhotos(skuId);
        if (!cancelled) {
          setUnits(response.units);
          setPhotoUrls(response.photoUrls);
        }
      } catch (e) {
        if (!cancelled) {
          setUnitsError(
            e instanceof Error ? e.message : 'Failed to load units',
          );
        }
      } finally {
        if (!cancelled) {
          setUnitsLoading(false);
        }
      }
    };

    fetchUnits();
    return () => {
      cancelled = true;
    };
  }, [skuId]);

  useEffect(() => {
    if (!sku) {
      return;
    }
    let cancelled = false;
    setCardImage(null);
    setCardImageError(null);
    setCardImageLoading(true);
    const fetchCardImage = async () => {
      try {
        const response = await apiClient.findCardImages({
          game: sku.game,
          external_ids: [sku.external_id],
        });
        if (!cancelled) {
          setCardImage(response.images[0].image_urls.normal);
        }
      } catch (e) {
        if (!cancelled) {
          setCardImageError(
            e instanceof Error ? e.message : 'Failed to load card image',
          );
        }
      } finally {
        if (!cancelled) {
          setCardImageLoading(false);
        }
      }
    };
    fetchCardImage();
    return () => {
      cancelled = true;
    };
  }, [sku?.game, sku?.external_id]);

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
      setUnitsLoading(true);
      setUnitsError(null);
      try {
        const updated = await loadUnitsWithPhotos(sku.sku_id);
        setUnits(updated.units);
        setPhotoUrls(updated.photoUrls);
      } catch (e) {
        setUnitsError(e instanceof Error ? e.message : 'Failed to load units');
        throw e;
      }
      setRemovingUnit(null);
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Failed to remove unit';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setUnitsLoading(false);
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
                <Stack className={classes.cardImage} gap="xs">
                  <Box className={classes.cardImageFrame}>
                    {cardImageLoading ? (
                      <Skeleton w="100%" h="100%" />
                    ) : (
                      <Image
                        src={cardImage ?? CARD_IMAGE_FALLBACK}
                        fallbackSrc={CARD_IMAGE_FALLBACK}
                        alt={sku.name}
                        w="100%"
                        h="100%"
                        fit="contain"
                        style={{ aspectRatio: CARD_IMAGE_ASPECT_RATIO }}
                        radius="sm"
                      />
                    )}
                  </Box>
                  {cardImageError && (
                    <Text size="xs" c="red">
                      {cardImageError}
                    </Text>
                  )}
                </Stack>
                <Stack gap="md" className={classes.overviewDetails}>
                  <div className={classes.attributes}>
                    <div>
                      <Text size="xs" c="dimmed" fw={600}>
                        Finish
                      </Text>
                      <Text size="sm">{getFinish(sku.game, sku.finish)}</Text>
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
                  {!unitsLoading &&
                    !unitsError &&
                    `${units.length} ${units.length === 1 ? 'unit' : 'units'}`}
                </Text>
              </Group>
              {unitsLoading ? (
                <Box p="md">
                  <Stack gap="xs">
                    <Skeleton height={36} />
                    <Skeleton height={36} />
                    <Skeleton height={36} />
                  </Stack>
                </Box>
              ) : unitsError ? (
                <Text c="red" size="sm" p="md">
                  {unitsError}
                </Text>
              ) : (
                <Box style={{ overflowX: 'auto' }}>
                  <UnitTable
                    units={units}
                    photoUrls={photoUrls}
                    onRemove={setRemovingUnit}
                    onEditCondition={setEditingUnit}
                  />
                </Box>
              )}
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
