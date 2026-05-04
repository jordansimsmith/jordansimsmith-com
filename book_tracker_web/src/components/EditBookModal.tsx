import { useEffect, useState } from 'react';
import { Button, Group, Modal, Stack, Text } from '@mantine/core';
import { DatePickerInput } from '@mantine/dates';
import { IconTrash } from '@tabler/icons-react';
import type { Book } from '../api/client';

interface EditBookModalProps {
  book: Book | null;
  opened: boolean;
  onClose: () => void;
  onSave: (book: Book, finishedDate: string) => Promise<void> | void;
  onDelete: (book: Book) => Promise<void> | void;
  saving?: boolean;
  deleting?: boolean;
}

const FINISHED_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

export function EditBookModal({
  book,
  opened,
  onClose,
  onSave,
  onDelete,
  saving = false,
  deleting = false,
}: EditBookModalProps) {
  const [finishedDate, setFinishedDate] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);

  useEffect(() => {
    if (book) {
      setFinishedDate(book.finished_date);
    }
    if (!opened) {
      setConfirmDelete(false);
    }
  }, [book, opened]);

  const handleSave = async () => {
    if (!book || !finishedDate) {
      return;
    }
    await onSave(book, finishedDate);
  };

  const handleConfirmDelete = async () => {
    if (!book) {
      return;
    }
    await onDelete(book);
  };

  const dateValid = !!finishedDate && FINISHED_DATE_PATTERN.test(finishedDate);
  const busy = saving || deleting;

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
        {confirmDelete ? (
          <Stack gap="sm">
            <Text size="sm">
              Remove{' '}
              <Text span fw={600}>
                {book?.title}
              </Text>{' '}
              from your library?
            </Text>
            <Group justify="flex-end">
              <Button
                variant="default"
                onClick={() => setConfirmDelete(false)}
                disabled={busy}
              >
                Cancel
              </Button>
              <Button
                color="red"
                onClick={handleConfirmDelete}
                loading={deleting}
                disabled={busy}
              >
                Delete
              </Button>
            </Group>
          </Stack>
        ) : (
          <Group justify="space-between">
            <Button
              variant="subtle"
              color="red"
              leftSection={<IconTrash size={16} />}
              onClick={() => setConfirmDelete(true)}
              disabled={busy}
            >
              Delete
            </Button>
            <Group>
              <Button variant="default" onClick={onClose} disabled={busy}>
                Cancel
              </Button>
              <Button
                onClick={handleSave}
                loading={saving}
                disabled={!dateValid || busy}
              >
                Save
              </Button>
            </Group>
          </Group>
        )}
      </Stack>
    </Modal>
  );
}
