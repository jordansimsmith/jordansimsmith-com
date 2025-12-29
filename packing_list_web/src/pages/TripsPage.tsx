import { useEffect, useState } from 'react';
import {
  Container,
  Title,
  Text,
  Button,
  Stack,
  Group,
  Skeleton,
  Divider,
  Box,
} from '@mantine/core';
import { IconPlane, IconPlus } from '@tabler/icons-react';
import { notifications } from '@mantine/notifications';
import { useNavigate } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { apiClient } from '../api/client';
import type { TripSummary } from '../api/client';

function formatDate(dateString: string): string {
  const date = new Date(dateString + 'T00:00:00');
  return date.toLocaleDateString(undefined, {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  });
}

function TripRow({ trip }: { trip: TripSummary }) {
  const navigate = useNavigate();

  return (
    <Box
      py="md"
      px="sm"
      style={{ cursor: 'pointer' }}
      onClick={() => navigate(`/trips/${trip.trip_id}`)}
    >
      <Group justify="space-between" wrap="nowrap">
        <Stack gap={4}>
          <Text fw={500} size="lg">
            {trip.name}
          </Text>
          <Text size="sm" c="dimmed">
            {trip.destination}
          </Text>
        </Stack>
        <Text size="sm" c="dimmed" ta="right" style={{ whiteSpace: 'nowrap' }}>
          {formatDate(trip.departure_date)} – {formatDate(trip.return_date)}
        </Text>
      </Group>
    </Box>
  );
}

function TripRowSkeleton() {
  return (
    <Box py="md" px="sm">
      <Group justify="space-between" wrap="nowrap">
        <Stack gap={4}>
          <Skeleton height={24} width={180} />
          <Skeleton height={16} width={120} />
        </Stack>
        <Skeleton height={16} width={200} />
      </Group>
    </Box>
  );
}

function EmptyState() {
  const navigate = useNavigate();

  return (
    <Stack align="center" gap="md" py="xl">
      <IconPlane size={64} stroke={1.5} color="var(--mantine-color-dimmed)" />
      <Text c="dimmed" ta="center">
        No trips yet. Create your first trip to get started.
      </Text>
      <Button
        leftSection={<IconPlus size={16} />}
        onClick={() => navigate('/trips/create')}
      >
        Create trip
      </Button>
    </Stack>
  );
}

export function TripsPage() {
  const navigate = useNavigate();
  const [trips, setTrips] = useState<TripSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchTrips = async () => {
      try {
        const response = await apiClient.getTrips();
        setTrips(response.trips);
      } catch (e) {
        const message = e instanceof Error ? e.message : 'Failed to load trips';
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

    fetchTrips();
  }, []);

  return (
    <AppShellLayout>
      <Container size="md" py="xl">
        <Group justify="space-between" mb="lg">
          <Title order={1}>Trips</Title>
          {!loading && trips.length > 0 && (
            <Button
              leftSection={<IconPlus size={16} />}
              onClick={() => navigate('/trips/create')}
            >
              Create trip
            </Button>
          )}
        </Group>

        {loading && (
          <Stack gap={0}>
            {[1, 2, 3].map((i) => (
              <div key={i}>
                <TripRowSkeleton />
                {i < 3 && <Divider />}
              </div>
            ))}
          </Stack>
        )}

        {!loading && error && (
          <Text c="red" ta="center">
            {error}
          </Text>
        )}

        {!loading && !error && trips.length === 0 && <EmptyState />}

        {!loading && !error && trips.length > 0 && (
          <Stack gap={0}>
            {trips.map((trip, index) => (
              <div key={trip.trip_id}>
                <TripRow trip={trip} />
                {index < trips.length - 1 && <Divider />}
              </div>
            ))}
          </Stack>
        )}
      </Container>
    </AppShellLayout>
  );
}
