import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { Login } from './Login';

function renderLogin(
  onSubmit: (values: {
    username: string;
    password: string;
  }) => void | Promise<void>,
) {
  return render(
    <MantineProvider>
      <Login appTitle="Test app" onSubmit={onSubmit} />
    </MantineProvider>,
  );
}

describe('Login', () => {
  afterEach(() => {
    cleanup();
  });

  it('renders the app title and form fields', () => {
    renderLogin(vi.fn());

    expect(screen.getByRole('heading', { name: /test app/i })).toBeDefined();
    expect(screen.getByLabelText(/username/i)).toBeDefined();
    expect(screen.getByLabelText(/password/i)).toBeDefined();
    expect(screen.getByRole('button', { name: /log in/i })).toBeDefined();
  });

  it('shows validation errors when fields are empty', async () => {
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    renderLogin(onSubmit);

    await user.click(screen.getByRole('button', { name: /log in/i }));

    await waitFor(() => {
      expect(screen.getByText(/username is required/i)).toBeDefined();
    });
    expect(screen.getByText(/password is required/i)).toBeDefined();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('shows a validation error when the username is only whitespace', async () => {
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    renderLogin(onSubmit);

    await user.type(screen.getByLabelText(/username/i), '   ');
    await user.type(screen.getByLabelText(/password/i), 'secret');
    await user.click(screen.getByRole('button', { name: /log in/i }));

    await waitFor(() => {
      expect(screen.getByText(/username is required/i)).toBeDefined();
    });
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('passes raw form values to onSubmit', async () => {
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    renderLogin(onSubmit);

    await user.type(screen.getByLabelText(/username/i), '  alice  ');
    await user.type(screen.getByLabelText(/password/i), 'secret');
    await user.click(screen.getByRole('button', { name: /log in/i }));

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledTimes(1);
    });
    expect(onSubmit).toHaveBeenCalledWith({
      username: '  alice  ',
      password: 'secret',
    });
  });

  it('shows a loading state while onSubmit is pending and clears it after it resolves', async () => {
    let resolve: () => void = () => {};
    const onSubmit = vi.fn(
      () =>
        new Promise<void>((r) => {
          resolve = r;
        }),
    );

    const user = userEvent.setup();
    renderLogin(onSubmit);

    await user.type(screen.getByLabelText(/username/i), 'alice');
    await user.type(screen.getByLabelText(/password/i), 'secret');
    await user.click(screen.getByRole('button', { name: /log in/i }));

    const button = screen.getByRole('button', { name: /log in/i });
    await waitFor(() => {
      expect(button.getAttribute('data-loading')).toBe('true');
    });

    resolve();

    await waitFor(() => {
      expect(button.getAttribute('data-loading')).not.toBe('true');
    });
  });
});
