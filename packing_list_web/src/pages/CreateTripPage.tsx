import { useEffect, useState } from 'react';
import {
  Container,
  Title,
  Text,
  Button,
  Stack,
  Group,
  Grid,
  Paper,
  Badge,
  Skeleton,
  ActionIcon,
  Modal,
  Divider,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import { IconArrowLeft, IconPlus, IconRefresh } from '@tabler/icons-react';
import { useNavigate } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { apiClient } from '../api/client';
import type { TemplatesResponse, TripItem } from '../api/client';
import {
  TripDetailsForm,
  type TripFormValues,
} from '../components/TripDetailsForm';
import { CategorySection } from '../components/CategorySection';
import {
  AddItemModal,
  type AddItemFormValues,
} from '../components/AddItemModal';
import {
  EditItemModal,
  type EditItemFormValues,
} from '../components/EditItemModal';
import { TemplatesPicker } from '../components/TemplatesPicker';
import { CreateTripPresenter } from '../presenters/create-trip-presenter';
import {
  groupAndSortItemsByCategory,
  getExistingCategories,
  getExistingTags,
} from '../domain/items';
import { normalizedName } from '../domain/normalize';

type ModalType = 'reset' | 'addItem' | 'editItem' | null;

const presenter = new CreateTripPresenter({ apiClient });

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
  const [activeModal, setActiveModal] = useState<ModalType>(null);
  const [editingItemKey, setEditingItemKey] = useState<string | null>(null);

  const tripForm = useForm<TripFormValues>({
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

  useEffect(() => {
    const fetchTemplates = async () => {
      try {
        const response = await presenter.loadTemplates();
        setTemplates(response);
        setItems(presenter.getBaseItems(response));
      } catch (e) {
        const message =
          e instanceof Error ? e.message : 'Failed to load templates';
        notifications.show({ title: 'Error', message, color: 'red' });
      } finally {
        setLoading(false);
      }
    };

    fetchTemplates();
  }, [presenter]);

  const handleApplyVariation = (variationId: string) => {
    if (!templates) return;
    const result = presenter.applyVariation(
      templates,
      items,
      addedVariations,
      variationId,
    );
    if (result) {
      setItems(result.items);
      setAddedVariations(result.addedVariations);
      setHasModifications(true);
    }
  };

  const handleReset = () => {
    if (!templates) return;
    const result = presenter.resetToBaseTemplate(templates);
    setItems(result.items);
    setAddedVariations(result.addedVariations);
    setHasModifications(false);
    setActiveModal(null);
  };

  const handleRemoveItem = (itemKey: string) => {
    setItems(presenter.removeItem(items, itemKey));
    setHasModifications(true);
  };

  const handleOpenAddItemModal = () => {
    setActiveModal('addItem');
  };

  const handleAddItem = (values: AddItemFormValues) => {
    setItems(presenter.addItem(items, values));
    setHasModifications(true);
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
    if (!editingItemKey) return;
    setItems(presenter.editItem(items, editingItemKey, values));
    setHasModifications(true);
    setEditingItemKey(null);
    editItemForm.reset();
    setActiveModal(null);
  };

  const handleCloseModal = () => {
    setActiveModal(null);
    setEditingItemKey(null);
  };

  const handleSubmitCreateTrip = async (values: TripFormValues) => {
    if (!values.departure_date || !values.return_date) return;

    setCreating(true);
    try {
      const tripId = await presenter.createTrip(
        {
          name: values.name,
          destination: values.destination,
          departure_date: values.departure_date,
          return_date: values.return_date,
        },
        items,
      );
      navigate(`/trips/${tripId}`);
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Failed to create trip';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setCreating(false);
    }
  };

  const { grouped, sortedCategories } = groupAndSortItemsByCategory(items);
  const existingCategories = getExistingCategories(items);
  const existingTags = getExistingTags(items);

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
          <form onSubmit={tripForm.onSubmit(handleSubmitCreateTrip)}>
            <Grid gutter="xl">
              <Grid.Col span={{ base: 12, md: 6 }}>
                <Stack gap="md">
                  <Paper p="md" withBorder>
                    <Stack gap="md">
                      <Title order={3}>Trip details</Title>
                      <TripDetailsForm form={tripForm} />
                    </Stack>
                  </Paper>

                  <Paper p="md" withBorder>
                    <Stack gap="md">
                      <Title order={3}>Variations</Title>
                      <Text size="sm" c="dimmed">
                        Add variations to include additional items. Once added,
                        variations cannot be removed.
                      </Text>
                      <TemplatesPicker
                        templates={templates}
                        addedVariations={addedVariations}
                        onApplyVariation={handleApplyVariation}
                      />
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
                            onClick={() => setActiveModal('reset')}
                            aria-label="Reset to base template"
                            title="Reset to base template"
                          >
                            <IconRefresh size={18} />
                          </ActionIcon>
                        )}
                      </Group>
                    </Group>

                    {sortedCategories.map((category) => (
                      <CategorySection
                        key={category}
                        category={category}
                        items={grouped.get(category) || []}
                        onEdit={handleOpenEditItem}
                        onRemove={handleRemoveItem}
                      />
                    ))}

                    <Divider my="xs" />

                    <Button
                      variant="light"
                      leftSection={<IconPlus size={16} />}
                      onClick={handleOpenAddItemModal}
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
          opened={activeModal === 'reset'}
          onClose={handleCloseModal}
          title="Reset packing list?"
          centered
        >
          <Stack>
            <Text size="sm">
              This will remove all added variations and restore the base
              template. Any removed or added items will be reset.
            </Text>
            <Group justify="flex-end">
              <Button variant="default" onClick={handleCloseModal}>
                Cancel
              </Button>
              <Button color="red" onClick={handleReset}>
                Reset
              </Button>
            </Group>
          </Stack>
        </Modal>

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
      </Container>
    </AppShellLayout>
  );
}
