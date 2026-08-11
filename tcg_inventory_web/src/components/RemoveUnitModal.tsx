import { useEffect, useState } from 'react';
import { Button, Group, Modal, Text, TextInput } from '@mantine/core';
import type { SkuUnit } from '../api/client';

interface RemoveUnitModalProps {
  unit: SkuUnit | null;
  loading: boolean;
  onCancel: () => void;
  onConfirm: (reason: string) => void;
}

export function RemoveUnitModal({
  unit,
  loading,
  onCancel,
  onConfirm,
}: RemoveUnitModalProps) {
  const [reason, setReason] = useState('');

  useEffect(() => {
    if (unit) {
      setReason('');
    }
  }, [unit]);

  return (
    <Modal
      opened={unit !== null}
      onClose={onCancel}
      title="Remove unit"
      centered
    >
      <Text mb="md">
        Remove the unit at{' '}
        <Text span fw={600}>
          {unit?.location}
        </Text>
        ? This cannot be undone.
      </Text>
      <TextInput
        label="Reason (optional)"
        value={reason}
        onChange={(event) => setReason(event.currentTarget.value)}
        mb="lg"
      />
      <Group justify="flex-end">
        <Button variant="default" onClick={onCancel} disabled={loading}>
          Cancel
        </Button>
        <Button
          color="red"
          onClick={() => onConfirm(reason.trim())}
          loading={loading}
        >
          Remove
        </Button>
      </Group>
    </Modal>
  );
}
