import { useEffect, useState, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Container,
  Title,
  Text,
  Stack,
  Group,
  Skeleton,
  Switch,
  Box,
  Divider,
  ActionIcon,
  Loader,
  Button,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import { IconArrowLeft, IconPencil, IconPlus } from '@tabler/icons-react';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { apiClient } from '../api/client';
import type { Trip, TripItem, TripItemStatus } from '../api/client';
import { formatDateDisplay, parseDateFromApi } from '../domain/dates';
import { CategorySection } from '../components/CategorySection';
import {
  AddItemModal,
  type AddItemFormValues,
} from '../components/AddItemModal';
import {
  EditItemModal,
  type EditItemFormValues,
} from '../components/EditItemModal';
import {
  TripDetailsModal,
  type TripFormValues,
} from '../components/TripDetailsModal';
import { TripPresenter } from '../presenters/trip-presenter';
import {
  groupAndSortItemsByCategory,
  getExistingCategories,
  getExistingTags,
} from '../domain/items';
import { normalizedName } from '../domain/normalize';

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

type ModalType = 'addItem' | 'editItem' | 'editTrip' | null;

const AUTOSAVE_DEBOUNCE_MS = 500;

const presenter = new TripPresenter({ apiClient });

export function TripPage() {
  const { tripId } = useParams<{ tripId: string }>();
  const navigate = useNavigate();
  const [trip, setTrip] = useState<Trip | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [hidePacked, setHidePacked] = useState(false);
  const [saving, setSaving] = useState(false);
  const [activeModal, setActiveModal] = useState<ModalType>(null);
  const [editingItemKey, setEditingItemKey] = useState<string | null>(null);

  const saveTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pendingTripRef = useRef<Trip | null>(null);

  const addItemForm = useForm<AddItemFormValues>({
    initialValues: {
      name: '',
      category: '',
      quantity: 1,
      tags: [],
    },
    validate: {
      name: (value) => (value.trim() ? null : 'Name is required'),
      quantity: (value) => (value >= 1 ? null : 'Quantity must be at least 1'),
    },
  });

  const editItemForm = useForm<EditItemFormValues>({
    initialValues: {
      category: '',
      quantity: 1,
      tags: [],
    },
    validate: {
      quantity: (value) => (value >= 1 ? null : 'Quantity must be at least 1'),
    },
  });

  const editTripForm = useForm<TripFormValues>({
    initialValues: {
      name: '',
      destination: '',
      departure_date: null,
      return_date: null,
    },
    validate: {
      name: (value) => (value.trim() ? null : 'Name is required'),
      destination: (value) => (value.trim() ? null : 'Destination is required'),
      departure_date: (value) => (value ? null : 'Departure date is required'),
      return_date: (value, values) => {
        if (!value) return 'Return date is required';
        if (values.departure_date && value < values.departure_date) {
          return 'Return date must be on or after departure date';
        }
        return null;
      },
    },
  });

  const saveTrip = async (tripToSave: Trip) => {
    setSaving(true);
    try {
      await presenter.saveTrip(tripToSave);
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Failed to save changes';
      notifications.show({ title: 'Error saving', message, color: 'red' });
    } finally {
      setSaving(false);
    }
  };

  const scheduleSave = (tripToSave: Trip) => {
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
  };

  useEffect(() => {
    return () => {
      if (saveTimeoutRef.current) {
        clearTimeout(saveTimeoutRef.current);
        if (pendingTripRef.current) {
          saveTrip(pendingTripRef.current);
        }
      }
    };
  }, []);

  useEffect(() => {
    const fetchTrip = async () => {
      if (!tripId) {
        setError('Invalid trip ID');
        setLoading(false);
        return;
      }

      try {
        const loadedTrip = await presenter.loadTrip(tripId);
        setTrip(loadedTrip);
      } catch (e) {
        const message = e instanceof Error ? e.message : 'Failed to load trip';
        setError(message);
        notifications.show({ title: 'Error', message, color: 'red' });
      } finally {
        setLoading(false);
      }
    };

    fetchTrip();
  }, [tripId]);

  const handleStatusChange = (itemName: string, newStatus: TripItemStatus) => {
    if (!trip) return;
    const updatedTrip = presenter.updateStatus(trip, itemName, newStatus);
    setTrip(updatedTrip);
    scheduleSave(updatedTrip);
  };

  const handleRemoveItem = (itemKey: string) => {
    if (!trip) return;
    const updatedTrip = presenter.removeItem(trip, itemKey);
    setTrip(updatedTrip);
    scheduleSave(updatedTrip);
  };

  const handleOpenAddItemModal = () => {
    setActiveModal('addItem');
  };

  const handleAddItem = (values: AddItemFormValues) => {
    if (!trip) return;
    const updatedTrip = presenter.addItem(trip, values);
    setTrip(updatedTrip);
    scheduleSave(updatedTrip);
    addItemForm.reset();
    setActiveModal(null);
  };

  const handleOpenEditItem = (item: TripItem) => {
    setEditingItemKey(normalizedName(item.name));
    editItemForm.setValues({
      category: item.category,
      quantity: item.quantity,
      tags: item.tags,
    });
    setActiveModal('editItem');
  };

  const handleEditItem = (values: EditItemFormValues) => {
    if (!trip || !editingItemKey) return;
    const updatedTrip = presenter.editItem(trip, editingItemKey, values);
    setTrip(updatedTrip);
    scheduleSave(updatedTrip);
    setEditingItemKey(null);
    editItemForm.reset();
    setActiveModal(null);
  };

  const handleOpenEditTrip = () => {
    if (!trip) return;
    editTripForm.setValues({
      name: trip.name,
      destination: trip.destination,
      departure_date: parseDateFromApi(trip.departure_date),
      return_date: parseDateFromApi(trip.return_date),
    });
    setActiveModal('editTrip');
  };

  const handleEditTrip = (values: TripFormValues) => {
    if (!trip || !values.departure_date || !values.return_date) return;
    const updatedTrip = presenter.editTripDetails(trip, {
      name: values.name,
      destination: values.destination,
      departure_date: values.departure_date,
      return_date: values.return_date,
    });
    setTrip(updatedTrip);
    scheduleSave(updatedTrip);
    editTripForm.reset();
    setActiveModal(null);
  };

  const handleCloseModal = () => {
    setActiveModal(null);
    setEditingItemKey(null);
  };

  const filteredItems = trip
    ? hidePacked
      ? trip.items.filter((item) => item.status !== 'packed')
      : trip.items
    : [];
  const { grouped, sortedCategories } =
    groupAndSortItemsByCategory(filteredItems);
  const existingCategories = trip ? getExistingCategories(trip.items) : [];
  const existingTags = trip ? getExistingTags(trip.items) : [];

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
            <>
              <Title order={1}>{trip?.name}</Title>
              <ActionIcon
                variant="subtle"
                onClick={handleOpenEditTrip}
                aria-label="Edit trip details"
              >
                <IconPencil size={20} />
              </ActionIcon>
            </>
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
                {formatDateDisplay(trip.departure_date)} –{' '}
                {formatDateDisplay(trip.return_date)}
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
                  items={grouped.get(category) || []}
                  onEdit={handleOpenEditItem}
                  onRemove={handleRemoveItem}
                  showStatusControl
                  onStatusChange={handleStatusChange}
                />
              ))}
            </Stack>

            <Divider my="xs" />

            <Button
              variant="light"
              leftSection={<IconPlus size={16} />}
              onClick={handleOpenAddItemModal}
              fullWidth
            >
              Add item
            </Button>
          </Stack>
        )}

        <AddItemModal
          opened={activeModal === 'addItem'}
          onClose={handleCloseModal}
          form={addItemForm}
          onSubmit={handleAddItem}
          existingCategories={existingCategories}
          existingTags={existingTags}
        />

        <EditItemModal
          opened={activeModal === 'editItem'}
          onClose={handleCloseModal}
          form={editItemForm}
          onSubmit={handleEditItem}
          existingCategories={existingCategories}
          existingTags={existingTags}
        />

        <TripDetailsModal
          opened={activeModal === 'editTrip'}
          onClose={handleCloseModal}
          form={editTripForm}
          onSubmit={handleEditTrip}
        />
      </Container>
    </AppShellLayout>
  );
}
