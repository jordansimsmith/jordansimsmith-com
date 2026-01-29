import { Modal, Stack, Group, Button } from '@mantine/core';
import type { UseFormReturnType } from '@mantine/form';
import { TripDetailsForm, type TripFormValues } from './TripDetailsForm';

export type { TripFormValues };

interface TripDetailsModalProps {
  opened: boolean;
  onClose: () => void;
  form: UseFormReturnType<TripFormValues>;
  onSubmit: (values: TripFormValues) => void;
  title?: string;
  submitLabel?: string;
}

export function TripDetailsModal({
  opened,
  onClose,
  form,
  onSubmit,
  title = 'Edit trip details',
  submitLabel = 'Save',
}: TripDetailsModalProps) {
  return (
    <Modal opened={opened} onClose={onClose} title={title} centered>
      <form onSubmit={form.onSubmit(onSubmit)}>
        <Stack>
          <TripDetailsForm form={form} nameAutoFocus />
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit">{submitLabel}</Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
