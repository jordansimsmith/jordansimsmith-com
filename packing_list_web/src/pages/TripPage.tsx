import { useEffect, useState, useRef, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Container,
  Title,
  Text,
  Stack,
  Group,
  Skeleton,
  Switch,
  Badge,
  SegmentedControl,
  Box,
  Divider,
  ActionIcon,
  Loader,
} from '@mantine/core';
import { IconArrowLeft } from '@tabler/icons-react';
import { notifications } from '@mantine/notifications';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { apiClient } from '../api/client';
import type { Trip, TripItem, TripItemStatus } from '../api/client';

function formatDate(dateString: string): string {
  const date = new Date(dateString + 'T00:00:00');
  return date.toLocaleDateString(undefined, {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  });
}

function getStatusColor(status: TripItemStatus): string {
  switch (status) {
    case 'packed':
      return 'teal';
    case 'pack-just-in-time':
      return 'yellow';
    case 'unpacked':
    default:
      return 'gray';
  }
}

interface ItemRowProps {
  item: TripItem;
  onStatusChange: (itemName: string, newStatus: TripItemStatus) => void;
}

function ItemRow({ item, onStatusChange }: ItemRowProps) {
  return (
    <Box py="sm" px="xs">
      <Group justify="space-between" wrap="nowrap" align="flex-start">
        <Stack gap={4} style={{ flex: 1 }}>
          <Group gap="xs">
            <Text
              fw={500}
              td={item.status === 'packed' ? 'line-through' : undefined}
              c={item.status === 'packed' ? 'dimmed' : undefined}
            >
              {item.name}
            </Text>
            {item.quantity > 1 && (
              <Badge size="sm" variant="light">
                ×{item.quantity}
              </Badge>
            )}
          </Group>
          {item.tags.length > 0 && (
            <Group gap={4}>
              {item.tags.map((tag) => (
                <Badge key={tag} size="xs" variant="outline" color="gray">
                  {tag}
                </Badge>
              ))}
            </Group>
          )}
        </Stack>
        <SegmentedControl
          size="xs"
          value={item.status}
          onChange={(value) =>
            onStatusChange(item.name, value as TripItemStatus)
          }
          data={[
            { label: 'Unpacked', value: 'unpacked' },
            { label: 'Pack later', value: 'pack-just-in-time' },
            { label: 'Packed', value: 'packed' },
          ]}
          color={getStatusColor(item.status)}
        />
      </Group>
    </Box>
  );
}

function ItemRowSkeleton() {
  return (
    <Box py="sm" px="xs">
      <Group justify="space-between" wrap="nowrap">
        <Stack gap={4}>
          <Skeleton height={20} width={150} />
          <Skeleton height={16} width={80} />
        </Stack>
        <Skeleton height={28} width={200} />
      </Group>
    </Box>
  );
}

interface CategorySectionProps {
  category: string;
  items: TripItem[];
  onStatusChange: (itemName: string, newStatus: TripItemStatus) => void;
}

function CategorySection({
  category,
  items,
  onStatusChange,
}: CategorySectionProps) {
  return (
    <Box>
      <Text fw={600} size="sm" c="dimmed" tt="uppercase" mb="xs">
        {category}
      </Text>
      <Stack gap={0}>
        {items.map((item, index) => (
          <div key={item.name}>
            <ItemRow item={item} onStatusChange={onStatusChange} />
            {index < items.length - 1 && <Divider />}
          </div>
        ))}
      </Stack>
    </Box>
  );
}

function groupItemsByCategory(items: TripItem[]): Map<string, TripItem[]> {
  const grouped = new Map<string, TripItem[]>();

  for (const item of items) {
    const existing = grouped.get(item.category) || [];
    existing.push(item);
    grouped.set(item.category, existing);
  }

  return grouped;
}

const AUTOSAVE_DEBOUNCE_MS = 500;

export function TripPage() {
  const { tripId } = useParams<{ tripId: string }>();
  const navigate = useNavigate();
  const [trip, setTrip] = useState<Trip | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [hidePacked, setHidePacked] = useState(false);
  const [saving, setSaving] = useState(false);

  const saveTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pendingTripRef = useRef<Trip | null>(null);

  const saveTrip = useCallback(async (tripToSave: Trip) => {
    setSaving(true);
    try {
      await apiClient.updateTrip({
        trip_id: tripToSave.trip_id,
        name: tripToSave.name,
        destination: tripToSave.destination,
        departure_date: tripToSave.departure_date,
        return_date: tripToSave.return_date,
        items: tripToSave.items,
      });
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Failed to save changes';
      notifications.show({
        title: 'Error saving',
        message,
        color: 'red',
      });
    } finally {
      setSaving(false);
    }
  }, []);

  const debouncedSave = useCallback(
    (tripToSave: Trip) => {
      pendingTripRef.current = tripToSave;

      if (saveTimeoutRef.current) {
        clearTimeout(saveTimeoutRef.current);
      }

      saveTimeoutRef.current = setTimeout(() => {
        if (pendingTripRef.current) {
          saveTrip(pendingTripRef.current);
          pendingTripRef.current = null;
        }
      }, AUTOSAVE_DEBOUNCE_MS);
    },
    [saveTrip],
  );

  useEffect(() => {
    return () => {
      if (saveTimeoutRef.current) {
        clearTimeout(saveTimeoutRef.current);
        if (pendingTripRef.current) {
          saveTrip(pendingTripRef.current);
        }
      }
    };
  }, [saveTrip]);

  useEffect(() => {
    const fetchTrip = async () => {
      if (!tripId) {
        setError('Invalid trip ID');
        setLoading(false);
        return;
      }

      try {
        const response = await apiClient.getTrip(tripId);
        setTrip(response.trip);
      } catch (e) {
        const message = e instanceof Error ? e.message : 'Failed to load trip';
        setError(message);
        notifications.show({
          title: 'Error',
          message,
          color: 'red',
        });
      } finally {
        setLoading(false);
      }
    };

    fetchTrip();
  }, [tripId]);

  const handleStatusChange = useCallback(
    (itemName: string, newStatus: TripItemStatus) => {
      if (!trip) return;

      const updatedItems = trip.items.map((item) =>
        item.name === itemName ? { ...item, status: newStatus } : item,
      );

      const updatedTrip = { ...trip, items: updatedItems };
      setTrip(updatedTrip);
      debouncedSave(updatedTrip);
    },
    [trip, debouncedSave],
  );

  const filteredItems = trip
    ? hidePacked
      ? trip.items.filter((item) => item.status !== 'packed')
      : trip.items
    : [];

  const groupedItems = groupItemsByCategory(filteredItems);
  const sortedCategories = Array.from(groupedItems.keys()).sort((a, b) =>
    a.localeCompare(b),
  );

  return (
    <AppShellLayout>
      <Container size="md" py="xl">
        <Group mb="lg" gap="sm">
          <ActionIcon
            variant="subtle"
            onClick={() => navigate('/trips')}
            aria-label="Back to trips"
          >
            <IconArrowLeft size={20} />
          </ActionIcon>
          {loading ? (
            <Skeleton height={32} width={200} />
          ) : (
            <Title order={1}>{trip?.name}</Title>
          )}
        </Group>

        {loading && (
          <Stack gap="md">
            <Skeleton height={24} width={300} />
            <Skeleton height={20} width={250} />
            <Box mt="md">
              {[1, 2, 3].map((i) => (
                <ItemRowSkeleton key={i} />
              ))}
            </Box>
          </Stack>
        )}

        {!loading && error && (
          <Text c="red" ta="center">
            {error}
          </Text>
        )}

        {!loading && !error && trip && (
          <Stack gap="lg">
            <Stack gap={4}>
              <Text size="lg" c="dimmed">
                {trip.destination}
              </Text>
              <Text size="sm" c="dimmed">
                {formatDate(trip.departure_date)} –{' '}
                {formatDate(trip.return_date)}
              </Text>
            </Stack>

            <Group justify="space-between" align="center">
              <Group gap="xs">
                <Text fw={500}>Packing list</Text>
                {saving && <Loader size="xs" />}
              </Group>
              <Switch
                label="Hide packed"
                checked={hidePacked}
                onChange={(event) => setHidePacked(event.currentTarget.checked)}
              />
            </Group>

            {sortedCategories.length === 0 && (
              <Text c="dimmed" ta="center" py="xl">
                {hidePacked
                  ? 'All items are packed!'
                  : 'No items in this trip.'}
              </Text>
            )}

            <Stack gap="xl">
              {sortedCategories.map((category) => (
                <CategorySection
                  key={category}
                  category={category}
                  items={groupedItems.get(category) || []}
                  onStatusChange={handleStatusChange}
                />
              ))}
            </Stack>
          </Stack>
        )}
      </Container>
    </AppShellLayout>
  );
}
