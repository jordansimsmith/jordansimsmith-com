import { useEffect, useState } from 'react';
import {
  Container,
  Title,
  Text,
  Button,
  Stack,
  Group,
  TextInput,
  Grid,
  Paper,
  Accordion,
  Badge,
  Skeleton,
  ActionIcon,
  Modal,
  CloseButton,
  NumberInput,
  Divider,
  Autocomplete,
  TagsInput,
} from '@mantine/core';
import { DatePickerInput } from '@mantine/dates';
import { useForm } from '@mantine/form';
import { useDisclosure } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import {
  IconArrowLeft,
  IconCheck,
  IconPencil,
  IconPlus,
  IconRefresh,
} from '@tabler/icons-react';
import { useNavigate } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { apiClient } from '../api/client';
import type { TemplatesResponse, TemplateItem, TripItem } from '../api/client';

interface TripFormValues {
  name: string;
  destination: string;
  departure_date: Date | null;
  return_date: Date | null;
}

function normalizedName(name: string): string {
  return name.toLowerCase().trim().replace(/\s+/g, ' ');
}

function mergeItems(
  existingItems: TripItem[],
  newItems: TemplateItem[],
): TripItem[] {
  const itemMap = new Map<string, TripItem>();

  for (const item of existingItems) {
    itemMap.set(normalizedName(item.name), { ...item });
  }

  for (const item of newItems) {
    const key = normalizedName(item.name);
    const existing = itemMap.get(key);
    if (existing) {
      existing.quantity += item.quantity;
      existing.tags = [...new Set([...existing.tags, ...item.tags])];
    } else {
      itemMap.set(key, {
        name: item.name,
        category: item.category,
        quantity: item.quantity,
        tags: [...item.tags],
        status: 'unpacked',
      });
    }
  }

  return Array.from(itemMap.values());
}

function formatDateForApi(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function groupItemsByCategory(items: TripItem[]): Map<string, TripItem[]> {
  const groups = new Map<string, TripItem[]>();
  for (const item of items) {
    const category = item.category || 'misc/uncategorised';
    if (!groups.has(category)) {
      groups.set(category, []);
    }
    groups.get(category)!.push(item);
  }
  return new Map(
    [...groups.entries()].sort((a, b) => a[0].localeCompare(b[0])),
  );
}

interface AddItemFormValues {
  name: string;
  category: string;
  quantity: number;
  tags: string[];
}

export function CreateTripPage() {
  const navigate = useNavigate();
  const [templates, setTemplates] = useState<TemplatesResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [items, setItems] = useState<TripItem[]>([]);
  const [addedVariations, setAddedVariations] = useState<Set<string>>(
    new Set(),
  );
  const [hasModifications, setHasModifications] = useState(false);
  const [resetModalOpened, { open: openResetModal, close: closeResetModal }] =
    useDisclosure(false);
  const [
    addItemModalOpened,
    { open: openAddItemModal, close: closeAddItemModal },
  ] = useDisclosure(false);
  const [
    editItemModalOpened,
    { open: openEditItemModal, close: closeEditItemModal },
  ] = useDisclosure(false);
  const [editingItemKey, setEditingItemKey] = useState<string | null>(null);

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

  const editItemForm = useForm<Omit<AddItemFormValues, 'name'>>({
    initialValues: {
      category: '',
      quantity: 1,
      tags: [],
    },
    validate: {
      quantity: (value) => (value >= 1 ? null : 'Quantity must be at least 1'),
    },
  });

  const existingCategories = Array.from(
    new Set(items.map((item) => item.category).filter(Boolean)),
  ).sort();

  const existingTags = Array.from(
    new Set(items.flatMap((item) => item.tags)),
  ).sort();

  const form = useForm<TripFormValues>({
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
      return_date: (value) => (value ? null : 'Return date is required'),
    },
  });

  useEffect(() => {
    const fetchTemplates = async () => {
      try {
        const response = await apiClient.getTemplates();
        setTemplates(response);
        const baseItems: TripItem[] = response.base_template.items.map(
          (item) => ({
            ...item,
            status: 'unpacked',
          }),
        );
        setItems(baseItems);
      } catch (e) {
        const message =
          e instanceof Error ? e.message : 'Failed to load templates';
        notifications.show({
          title: 'Error',
          message,
          color: 'red',
        });
      } finally {
        setLoading(false);
      }
    };

    fetchTemplates();
  }, []);

  const handleAddVariation = (variationId: string) => {
    if (!templates || addedVariations.has(variationId)) return;

    const variation = templates.variations.find(
      (v) => v.variation_id === variationId,
    );
    if (!variation) return;

    setItems((current) => mergeItems(current, variation.items));
    setAddedVariations((current) => new Set([...current, variationId]));
    setHasModifications(true);
  };

  const handleReset = () => {
    if (!templates) return;
    const baseItems: TripItem[] = templates.base_template.items.map((item) => ({
      ...item,
      status: 'unpacked',
    }));
    setItems(baseItems);
    setAddedVariations(new Set());
    setHasModifications(false);
    closeResetModal();
  };

  const handleRemoveItem = (itemName: string) => {
    setItems((current) =>
      current.filter((item) => normalizedName(item.name) !== itemName),
    );
    setHasModifications(true);
  };

  const handleAddItem = (values: AddItemFormValues) => {
    const newItem: TripItem = {
      name: values.name.trim(),
      category: values.category?.trim() || 'misc/uncategorised',
      quantity: values.quantity,
      tags: values.tags,
      status: 'unpacked',
    };

    const key = normalizedName(newItem.name);
    const existingItem = items.find(
      (item) => normalizedName(item.name) === key,
    );

    if (existingItem) {
      setItems((current) =>
        current.map((item) =>
          normalizedName(item.name) === key
            ? {
                ...item,
                quantity: item.quantity + newItem.quantity,
                tags: [...new Set([...item.tags, ...newItem.tags])],
              }
            : item,
        ),
      );
    } else {
      setItems((current) => [...current, newItem]);
    }

    setHasModifications(true);
    addItemForm.reset();
    closeAddItemModal();
  };

  const handleOpenEditItem = (item: TripItem) => {
    setEditingItemKey(normalizedName(item.name));
    editItemForm.setValues({
      category: item.category,
      quantity: item.quantity,
      tags: item.tags,
    });
    openEditItemModal();
  };

  const handleEditItem = (values: Omit<AddItemFormValues, 'name'>) => {
    if (!editingItemKey) return;

    setItems((current) =>
      current.map((item) =>
        normalizedName(item.name) === editingItemKey
          ? {
              ...item,
              category: values.category?.trim() || 'misc/uncategorised',
              quantity: values.quantity,
              tags: values.tags,
            }
          : item,
      ),
    );

    setHasModifications(true);
    setEditingItemKey(null);
    editItemForm.reset();
    closeEditItemModal();
  };

  const handleSubmit = async (values: TripFormValues) => {
    if (!values.departure_date || !values.return_date) return;

    setCreating(true);
    try {
      const response = await apiClient.createTrip({
        name: values.name.trim(),
        destination: values.destination.trim(),
        departure_date: formatDateForApi(values.departure_date),
        return_date: formatDateForApi(values.return_date),
        items,
      });
      navigate(`/trips/${response.trip.trip_id}`);
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Failed to create trip';
      notifications.show({
        title: 'Error',
        message,
        color: 'red',
      });
    } finally {
      setCreating(false);
    }
  };

  const groupedItems = groupItemsByCategory(items);

  return (
    <AppShellLayout>
      <Container size="lg" py="xl">
        <Group mb="lg">
          <ActionIcon
            variant="subtle"
            onClick={() => navigate('/trips')}
            aria-label="Back to trips"
          >
            <IconArrowLeft size={20} />
          </ActionIcon>
          <Title order={1}>Create trip</Title>
        </Group>

        {loading && (
          <Stack gap="md">
            <Skeleton height={40} />
            <Skeleton height={40} />
            <Skeleton height={40} />
            <Skeleton height={40} />
            <Skeleton height={200} />
          </Stack>
        )}

        {!loading && templates && (
          <form onSubmit={form.onSubmit(handleSubmit)}>
            <Grid gutter="xl">
              <Grid.Col span={{ base: 12, md: 6 }}>
                <Stack gap="md">
                  <Paper p="md" withBorder>
                    <Stack gap="md">
                      <Title order={3}>Trip details</Title>
                      <TextInput
                        label="Name"
                        placeholder="Japan 2025"
                        {...form.getInputProps('name')}
                      />
                      <TextInput
                        label="Destination"
                        placeholder="Tokyo"
                        {...form.getInputProps('destination')}
                      />
                      <DatePickerInput
                        label="Departure date"
                        placeholder="Select date"
                        valueFormat="DD MMM YYYY"
                        {...form.getInputProps('departure_date')}
                      />
                      <DatePickerInput
                        label="Return date"
                        placeholder="Select date"
                        valueFormat="DD MMM YYYY"
                        {...form.getInputProps('return_date')}
                      />
                    </Stack>
                  </Paper>

                  <Paper p="md" withBorder>
                    <Stack gap="md">
                      <Title order={3}>Variations</Title>
                      <Text size="sm" c="dimmed">
                        Add variations to include additional items. Once added,
                        variations cannot be removed.
                      </Text>
                      <Accordion>
                        {templates.variations.map((variation) => (
                          <Accordion.Item
                            key={variation.variation_id}
                            value={variation.variation_id}
                          >
                            <Accordion.Control>
                              <Group justify="space-between" wrap="nowrap">
                                <Group gap="xs">
                                  <Text>{variation.name}</Text>
                                  <Badge size="sm" variant="light">
                                    {variation.items.length} items
                                  </Badge>
                                </Group>
                                {addedVariations.has(
                                  variation.variation_id,
                                ) && (
                                  <Badge color="teal" size="sm">
                                    Added
                                  </Badge>
                                )}
                              </Group>
                            </Accordion.Control>
                            <Accordion.Panel>
                              <Stack gap="xs">
                                {variation.items.map((item) => (
                                  <Text key={item.name} size="sm">
                                    {item.name}{' '}
                                    {item.quantity > 1 && `(×${item.quantity})`}
                                  </Text>
                                ))}
                                {!addedVariations.has(
                                  variation.variation_id,
                                ) && (
                                  <Button
                                    size="xs"
                                    variant="light"
                                    leftSection={<IconCheck size={14} />}
                                    onClick={() =>
                                      handleAddVariation(variation.variation_id)
                                    }
                                  >
                                    Apply variation
                                  </Button>
                                )}
                              </Stack>
                            </Accordion.Panel>
                          </Accordion.Item>
                        ))}
                      </Accordion>
                    </Stack>
                  </Paper>
                </Stack>
              </Grid.Col>

              <Grid.Col span={{ base: 12, md: 6 }}>
                <Paper p="md" withBorder>
                  <Stack gap="md">
                    <Group justify="space-between">
                      <Title order={3}>Packing list preview</Title>
                      <Group gap="xs">
                        <Badge size="lg">{items.length} items</Badge>
                        {hasModifications && (
                          <ActionIcon
                            variant="subtle"
                            color="red"
                            onClick={openResetModal}
                            aria-label="Reset to base template"
                            title="Reset to base template"
                          >
                            <IconRefresh size={18} />
                          </ActionIcon>
                        )}
                      </Group>
                    </Group>

                    {Array.from(groupedItems.entries()).map(
                      ([category, categoryItems]) => (
                        <Stack key={category} gap="xs">
                          <Text fw={500} tt="capitalize">
                            {category}
                          </Text>
                          {categoryItems.map((item) => (
                            <Group
                              key={item.name}
                              gap="xs"
                              pl="md"
                              justify="space-between"
                              wrap="nowrap"
                            >
                              <Text size="sm" style={{ flexShrink: 1 }}>
                                {item.name}
                              </Text>
                              <Group gap="xs" wrap="nowrap">
                                {item.quantity > 1 && (
                                  <Badge size="xs" variant="light">
                                    ×{item.quantity}
                                  </Badge>
                                )}
                                {item.tags.map((tag) => (
                                  <Badge
                                    key={tag}
                                    size="xs"
                                    variant="outline"
                                    color="gray"
                                  >
                                    {tag}
                                  </Badge>
                                ))}
                                <ActionIcon
                                  size="xs"
                                  variant="subtle"
                                  aria-label={`Edit ${item.name}`}
                                  onClick={() => handleOpenEditItem(item)}
                                >
                                  <IconPencil size={14} />
                                </ActionIcon>
                                <CloseButton
                                  size="xs"
                                  aria-label={`Remove ${item.name}`}
                                  onClick={() =>
                                    handleRemoveItem(normalizedName(item.name))
                                  }
                                />
                              </Group>
                            </Group>
                          ))}
                        </Stack>
                      ),
                    )}

                    <Divider my="xs" />

                    <Button
                      variant="light"
                      leftSection={<IconPlus size={16} />}
                      onClick={openAddItemModal}
                      fullWidth
                    >
                      Add item
                    </Button>

                    <Button type="submit" fullWidth loading={creating}>
                      Create trip
                    </Button>
                  </Stack>
                </Paper>
              </Grid.Col>
            </Grid>
          </form>
        )}

        <Modal
          opened={resetModalOpened}
          onClose={closeResetModal}
          title="Reset packing list?"
          centered
        >
          <Stack>
            <Text size="sm">
              This will remove all added variations and restore the base
              template. Any removed or added items will be reset.
            </Text>
            <Group justify="flex-end">
              <Button variant="default" onClick={closeResetModal}>
                Cancel
              </Button>
              <Button color="red" onClick={handleReset}>
                Reset
              </Button>
            </Group>
          </Stack>
        </Modal>

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
