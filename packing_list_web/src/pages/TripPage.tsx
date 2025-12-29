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
  Button,
  Modal,
  TextInput,
  Autocomplete,
  NumberInput,
  TagsInput,
  CloseButton,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { useDisclosure } from '@mantine/hooks';
import { IconArrowLeft, IconPencil, IconPlus } from '@tabler/icons-react';
import { notifications } from '@mantine/notifications';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { apiClient } from '../api/client';
import type { Trip, TripItem, TripItemStatus } from '../api/client';

function normalizedName(name: string): string {
  return name.toLowerCase().trim().replace(/\s+/g, ' ');
}

interface AddItemFormValues {
  name: string;
  category: string;
  quantity: number;
  tags: string[];
}

interface EditItemFormValues {
  category: string;
  quantity: number;
  tags: string[];
}

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
  onEdit: (item: TripItem) => void;
  onRemove: (itemName: string) => void;
}

function ItemRow({ item, onStatusChange, onEdit, onRemove }: ItemRowProps) {
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
        <Group gap="xs" wrap="nowrap">
          <ActionIcon
            size="xs"
            variant="subtle"
            aria-label={`Edit ${item.name}`}
            onClick={() => onEdit(item)}
          >
            <IconPencil size={14} />
          </ActionIcon>
          <CloseButton
            size="xs"
            aria-label={`Remove ${item.name}`}
            onClick={() => onRemove(normalizedName(item.name))}
          />
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
  onEdit: (item: TripItem) => void;
  onRemove: (itemName: string) => void;
}

function CategorySection({
  category,
  items,
  onStatusChange,
  onEdit,
  onRemove,
}: CategorySectionProps) {
  return (
    <Box>
      <Text fw={600} size="sm" c="dimmed" tt="uppercase" mb="xs">
        {category}
      </Text>
      <Stack gap={0}>
        {items.map((item, index) => (
          <div key={item.name}>
            <ItemRow
              item={item}
              onStatusChange={onStatusChange}
              onEdit={onEdit}
              onRemove={onRemove}
            />
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
  const [editingItemKey, setEditingItemKey] = useState<string | null>(null);
  const [
    addItemModalOpened,
    { open: openAddItemModal, close: closeAddItemModal },
  ] = useDisclosure(false);
  const [
    editItemModalOpened,
    { open: openEditItemModal, close: closeEditItemModal },
  ] = useDisclosure(false);

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

  const existingCategories = trip
    ? Array.from(
        new Set(trip.items.map((item) => item.category).filter(Boolean)),
      ).sort()
    : [];

  const existingTags = trip
    ? Array.from(new Set(trip.items.flatMap((item) => item.tags))).sort()
    : [];

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

  const handleRemoveItem = useCallback(
    (itemKey: string) => {
      if (!trip) return;

      const updatedItems = trip.items.filter(
        (item) => normalizedName(item.name) !== itemKey,
      );

      const updatedTrip = { ...trip, items: updatedItems };
      setTrip(updatedTrip);
      debouncedSave(updatedTrip);
    },
    [trip, debouncedSave],
  );

  const handleOpenEditItem = useCallback(
    (item: TripItem) => {
      setEditingItemKey(normalizedName(item.name));
      editItemForm.setValues({
        category: item.category,
        quantity: item.quantity,
        tags: item.tags,
      });
      openEditItemModal();
    },
    [editItemForm, openEditItemModal],
  );

  const handleEditItem = useCallback(
    (values: EditItemFormValues) => {
      if (!trip || !editingItemKey) return;

      const updatedItems = trip.items.map((item) =>
        normalizedName(item.name) === editingItemKey
          ? {
              ...item,
              category: values.category?.trim() || 'misc/uncategorised',
              quantity: values.quantity,
              tags: values.tags,
            }
          : item,
      );

      const updatedTrip = { ...trip, items: updatedItems };
      setTrip(updatedTrip);
      debouncedSave(updatedTrip);

      setEditingItemKey(null);
      editItemForm.reset();
      closeEditItemModal();
    },
    [trip, editingItemKey, debouncedSave, editItemForm, closeEditItemModal],
  );

  const handleAddItem = useCallback(
    (values: AddItemFormValues) => {
      if (!trip) return;

      const newItem: TripItem = {
        name: values.name.trim(),
        category: values.category?.trim() || 'misc/uncategorised',
        quantity: values.quantity,
        tags: values.tags,
        status: 'unpacked',
      };

      const key = normalizedName(newItem.name);
      const existingItem = trip.items.find(
        (item) => normalizedName(item.name) === key,
      );

      let updatedItems: TripItem[];
      if (existingItem) {
        updatedItems = trip.items.map((item) =>
          normalizedName(item.name) === key
            ? {
                ...item,
                quantity: item.quantity + newItem.quantity,
                tags: [...new Set([...item.tags, ...newItem.tags])],
              }
            : item,
        );
      } else {
        updatedItems = [...trip.items, newItem];
      }

      const updatedTrip = { ...trip, items: updatedItems };
      setTrip(updatedTrip);
      debouncedSave(updatedTrip);

      addItemForm.reset();
      closeAddItemModal();
    },
    [trip, debouncedSave, addItemForm, closeAddItemModal],
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
                  onEdit={handleOpenEditItem}
                  onRemove={handleRemoveItem}
                />
              ))}
            </Stack>

            <Divider my="xs" />

            <Button
              variant="light"
              leftSection={<IconPlus size={16} />}
              onClick={openAddItemModal}
              fullWidth
            >
              Add item
            </Button>
          </Stack>
        )}

        <Modal
          opened={addItemModalOpened}
          onClose={closeAddItemModal}
          title="Add item"
          centered
        >
          <form onSubmit={addItemForm.onSubmit(handleAddItem)}>
            <Stack>
              <TextInput
                label="Name"
                placeholder="Item name"
                data-autofocus
                {...addItemForm.getInputProps('name')}
              />
              <Autocomplete
                label="Category"
                placeholder="Select or type to create"
                data={existingCategories}
                {...addItemForm.getInputProps('category')}
              />
              <NumberInput
                label="Quantity"
                min={1}
                {...addItemForm.getInputProps('quantity')}
              />
              <TagsInput
                label="Tags"
                placeholder="Select or type to add"
                data={existingTags}
                {...addItemForm.getInputProps('tags')}
              />
              <Group justify="flex-end">
                <Button variant="default" onClick={closeAddItemModal}>
                  Cancel
                </Button>
                <Button type="submit">Add</Button>
              </Group>
            </Stack>
          </form>
        </Modal>

        <Modal
          opened={editItemModalOpened}
          onClose={closeEditItemModal}
          title="Edit item"
          centered
        >
          <form onSubmit={editItemForm.onSubmit(handleEditItem)}>
            <Stack>
              <Autocomplete
                label="Category"
                placeholder="Select or type to create"
                data={existingCategories}
                {...editItemForm.getInputProps('category')}
              />
              <NumberInput
                label="Quantity"
                min={1}
                {...editItemForm.getInputProps('quantity')}
              />
              <TagsInput
                label="Tags"
                placeholder="Select or type to add"
                data={existingTags}
                {...editItemForm.getInputProps('tags')}
              />
              <Group justify="flex-end">
                <Button variant="default" onClick={closeEditItemModal}>
                  Cancel
                </Button>
                <Button type="submit">Save</Button>
              </Group>
            </Stack>
          </form>
        </Modal>
      </Container>
    </AppShellLayout>
  );
}
