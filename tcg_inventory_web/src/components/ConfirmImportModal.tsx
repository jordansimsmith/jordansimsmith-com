import { Button, Group, List, Modal, Text } from '@mantine/core';

interface ConfirmImportModalProps {
  opened: boolean;
  keepCount: number;
  discardCount: number;
  reviewCount: number;
  loading: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}

export function ConfirmImportModal({
  opened,
  keepCount,
  discardCount,
  reviewCount,
  loading,
  onCancel,
  onConfirm,
}: ConfirmImportModalProps) {
  return (
    <Modal opened={opened} onClose={onCancel} title="Confirm import" centered>
      <Text mb="md">
        Add{' '}
        <Text span fw={600}>
          {keepCount}
        </Text>{' '}
        keeper {keepCount === 1 ? 'card' : 'cards'} to inventory? This cannot be
        undone.
      </Text>
      {(discardCount > 0 || reviewCount > 0) && (
        <List mb="md" size="sm" spacing="xs">
          {discardCount > 0 && (
            <List.Item>
              Pull the {discardCount} discard{' '}
              {discardCount === 1 ? 'card' : 'cards'} out of the stack.
            </List.Item>
          )}
          {reviewCount > 0 && (
            <List.Item>
              Set aside the {reviewCount} review{' '}
              {reviewCount === 1 ? 'card' : 'cards'} — they are not added to
              inventory. Fix the cause and re-import them later.
            </List.Item>
          )}
        </List>
      )}
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
