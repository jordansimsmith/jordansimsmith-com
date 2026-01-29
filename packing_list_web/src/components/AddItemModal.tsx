import {
  Modal,
  Stack,
  TextInput,
  Autocomplete,
  NumberInput,
  TagsInput,
  Group,
  Button,
} from '@mantine/core';
import type { UseFormReturnType } from '@mantine/form';

export interface AddItemFormValues {
  name: string;
  category: string;
  quantity: number;
  tags: string[];
}

interface AddItemModalProps {
  opened: boolean;
  onClose: () => void;
  form: UseFormReturnType<AddItemFormValues>;
  onSubmit: (values: AddItemFormValues) => void;
  existingCategories: string[];
  existingTags: string[];
}

export function AddItemModal({
  opened,
  onClose,
  form,
  onSubmit,
  existingCategories,
  existingTags,
}: AddItemModalProps) {
  return (
    <Modal opened={opened} onClose={onClose} title="Add item" centered>
      <form onSubmit={form.onSubmit(onSubmit)}>
        <Stack>
          <TextInput
            label="Name"
            placeholder="Item name"
            data-autofocus
            {...form.getInputProps('name')}
          />
          <Autocomplete
            label="Category"
            placeholder="Select or type to create"
            data={existingCategories}
            {...form.getInputProps('category')}
          />
          <NumberInput
            label="Quantity"
            min={1}
            {...form.getInputProps('quantity')}
          />
          <TagsInput
            label="Tags"
            placeholder="Select or type to add"
            data={existingTags}
            {...form.getInputProps('tags')}
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit">Add</Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
