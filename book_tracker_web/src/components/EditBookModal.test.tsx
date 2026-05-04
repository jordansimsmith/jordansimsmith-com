import { render, screen, cleanup, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { DatesProvider } from '@mantine/dates';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { EditBookModal } from './EditBookModal';
import type { Book } from '../api/client';

const sampleBook: Book = {
  open_library_work_id: 'OL27448W',
  title: 'The Lord of the Rings',
  authors: ['J.R.R. Tolkien'],
  cover_url: null,
  page_count: 1193,
  publication_year: 1954,
  finished_date: '2026-04-28',
  created_at: 1714809600,
  updated_at: 1714809600,
};

function renderModal({
  book = sampleBook,
  opened = true,
  onClose = vi.fn(),
  onSave = vi.fn(),
  onDelete = vi.fn(),
  saving = false,
  deleting = false,
}: Partial<React.ComponentProps<typeof EditBookModal>> = {}) {
  return {
    ...render(
      <MantineProvider>
        <DatesProvider settings={{}}>
          <EditBookModal
            book={book}
            opened={opened}
            onClose={onClose}
            onSave={onSave}
            onDelete={onDelete}
            saving={saving}
            deleting={deleting}
          />
        </DatesProvider>
      </MantineProvider>,
    ),
    onClose,
    onSave,
    onDelete,
  };
}

describe('EditBookModal', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  it('renders the title and prefilled finished date', () => {
    renderModal();

    expect(screen.getByText(/edit the lord of the rings/i)).toBeDefined();
    expect(screen.getByText('J.R.R. Tolkien')).toBeDefined();
    expect(screen.getByText(/finished date/i)).toBeDefined();
    expect(screen.getByText(/28 apr 2026/i)).toBeDefined();
  });

  it('calls onSave with the new finished date', async () => {
    const user = userEvent.setup();
    const onSave = vi.fn().mockResolvedValue(undefined);
    renderModal({ onSave });

    const saveButton = screen.getByRole('button', { name: /^save$/i });
    await user.click(saveButton);

    await waitFor(() =>
      expect(onSave).toHaveBeenCalledWith(sampleBook, sampleBook.finished_date),
    );
  });

  it('calls onClose when Cancel is clicked', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderModal({ onClose });

    await user.click(screen.getByRole('button', { name: /cancel/i }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('disables Save while saving is in progress', () => {
    renderModal({ saving: true });

    const saveButton = screen.getByRole('button', { name: /^save$/i });
    expect(saveButton.hasAttribute('disabled')).toBe(true);
  });

  it('shows the confirmation message when Delete is clicked', async () => {
    const user = userEvent.setup();
    renderModal();

    await user.click(screen.getByRole('button', { name: /^delete$/i }));

    expect(screen.getByText(/remove/i)).toBeDefined();
    expect(screen.getByText(/from your library\?/i)).toBeDefined();
  });

  it('calls onDelete after the confirmation Delete button is clicked', async () => {
    const user = userEvent.setup();
    const onDelete = vi.fn().mockResolvedValue(undefined);
    renderModal({ onDelete });

    await user.click(screen.getByRole('button', { name: /^delete$/i }));
    const confirmDelete = screen.getByRole('button', { name: /^delete$/i });
    await user.click(confirmDelete);

    await waitFor(() => expect(onDelete).toHaveBeenCalledWith(sampleBook));
  });

  it('returns to the save view when the confirmation is cancelled', async () => {
    const user = userEvent.setup();
    const onDelete = vi.fn();
    renderModal({ onDelete });

    await user.click(screen.getByRole('button', { name: /^delete$/i }));
    expect(screen.getByText(/from your library\?/i)).toBeDefined();

    const cancelButtons = screen.getAllByRole('button', { name: /cancel/i });
    await user.click(cancelButtons[cancelButtons.length - 1]);

    expect(screen.queryByText(/from your library\?/i)).toBeNull();
    expect(screen.getByRole('button', { name: /^save$/i })).toBeDefined();
    expect(onDelete).not.toHaveBeenCalled();
  });
});
