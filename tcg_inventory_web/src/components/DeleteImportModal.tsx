import { Button, Group, Modal, Text } from '@mantine/core';

interface DeleteImportModalProps {
  opened: boolean;
  filename: string;
  rowCount: number;
  loading: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}

export function DeleteImportModal({
  opened,
  filename,
  rowCount,
  loading,
  onCancel,
  onConfirm,
}: DeleteImportModalProps) {
  return (
    <Modal opened={opened} onClose={onCancel} title="Delete import" centered>
      <Text mb="md">
        Delete{' '}
        <Text span fw={600}>
          {filename}
        </Text>{' '}
        and its {rowCount} {rowCount === 1 ? 'row' : 'rows'}? This cannot be
        undone.
      </Text>
      <Group justify="flex-end">
        <Button variant="default" onClick={onCancel} disabled={loading}>
          Cancel
        </Button>
        <Button color="red" onClick={onConfirm} loading={loading}>
          Delete
        </Button>
      </Group>
    </Modal>
  );
}
