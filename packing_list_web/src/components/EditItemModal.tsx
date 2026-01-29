import {
  Modal,
  Stack,
  Autocomplete,
  NumberInput,
  TagsInput,
  Group,
  Button,
} from '@mantine/core';
import type { UseFormReturnType } from '@mantine/form';

export interface EditItemFormValues {
  category: string;
  quantity: number;
  tags: string[];
}

interface EditItemModalProps {
  opened: boolean;
  onClose: () => void;
  form: UseFormReturnType<EditItemFormValues>;
  onSubmit: (values: EditItemFormValues) => void;
  existingCategories: string[];
  existingTags: string[];
}

export function EditItemModal({
  opened,
  onClose,
  form,
  onSubmit,
  existingCategories,
  existingTags,
}: EditItemModalProps) {
  return (
    <Modal opened={opened} onClose={onClose} title="Edit item" centered>
      <form onSubmit={form.onSubmit(onSubmit)}>
        <Stack>
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
            <Button type="submit">Save</Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
