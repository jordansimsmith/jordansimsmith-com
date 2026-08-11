import { useEffect, useState } from 'react';
import { Button, Group, Modal, Select } from '@mantine/core';
import { CONDITIONS } from '../api/client';
import type { Condition, SkuUnit } from '../api/client';

interface EditConditionModalProps {
  unit: SkuUnit | null;
  currentCondition: Condition;
  loading: boolean;
  onCancel: () => void;
  onConfirm: (condition: Condition) => void;
}

export function EditConditionModal({
  unit,
  currentCondition,
  loading,
  onCancel,
  onConfirm,
}: EditConditionModalProps) {
  const [condition, setCondition] = useState<string | null>(null);

  useEffect(() => {
    if (unit) {
      setCondition(null);
    }
  }, [unit]);

  return (
    <Modal
      opened={unit !== null}
      onClose={onCancel}
      title="Edit condition"
      centered
    >
      <Select
        label="New condition"
        placeholder="Select condition"
        data={CONDITIONS.filter((option) => option !== currentCondition)}
        value={condition}
        onChange={setCondition}
        mb="lg"
      />
      <Group justify="flex-end">
        <Button variant="default" onClick={onCancel} disabled={loading}>
          Cancel
        </Button>
        <Button
          onClick={() => condition && onConfirm(condition as Condition)}
          disabled={!condition}
          loading={loading}
        >
          Save
        </Button>
      </Group>
    </Modal>
  );
}
