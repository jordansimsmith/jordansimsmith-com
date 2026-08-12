import { Button, Group, Modal, Text } from '@mantine/core';

interface ConfirmPullModalProps {
  opened: boolean;
  unitCount: number;
  loading: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}

export function ConfirmPullModal({
  opened,
  unitCount,
  loading,
  onCancel,
  onConfirm,
}: ConfirmPullModalProps) {
  return (
    <Modal opened={opened} onClose={onCancel} title="Confirm pull" centered>
      <Text mb="md">
        Mark all{' '}
        <Text span fw={600}>
          {unitCount}
        </Text>{' '}
        {unitCount === 1 ? 'card' : 'cards'} as pulled? The units are marked
        sold and this cannot be undone.
      </Text>
      <Group justify="flex-end">
        <Button variant="default" onClick={onCancel} disabled={loading}>
          Cancel
        </Button>
        <Button onClick={onConfirm} loading={loading}>
          Confirm
        </Button>
      </Group>
    </Modal>
  );
}
