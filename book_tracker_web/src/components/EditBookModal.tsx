import { useEffect, useState } from 'react';
import { Button, Group, Modal, Stack, Text } from '@mantine/core';
import { DatePickerInput } from '@mantine/dates';
import type { Book } from '../api/client';

interface EditBookModalProps {
  book: Book | null;
  opened: boolean;
  onClose: () => void;
  onSave: (book: Book, finishedDate: string) => Promise<void> | void;
  saving?: boolean;
}

const FINISHED_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

export function EditBookModal({
  book,
  opened,
  onClose,
  onSave,
  saving = false,
}: EditBookModalProps) {
  const [finishedDate, setFinishedDate] = useState<string | null>(null);

  useEffect(() => {
    if (book) {
      setFinishedDate(book.finished_date);
    }
  }, [book]);

  const handleSave = async () => {
    if (!book || !finishedDate) {
      return;
    }
    await onSave(book, finishedDate);
  };

  const dateValid = !!finishedDate && FINISHED_DATE_PATTERN.test(finishedDate);

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={book ? `Edit ${book.title}` : 'Edit book'}
      centered
    >
      <Stack>
        {book && (
          <Text size="sm" c="dimmed">
            {book.authors.length > 0
              ? book.authors.join(', ')
              : 'Unknown author'}
          </Text>
        )}
        <DatePickerInput
          label="Finished date"
          placeholder="Select date"
          valueFormat="DD MMM YYYY"
          value={finishedDate}
          onChange={(value) => setFinishedDate(value)}
        />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose} disabled={saving}>
            Cancel
          </Button>
          <Button
            onClick={handleSave}
            loading={saving}
            disabled={!dateValid || saving}
          >
            Save
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
