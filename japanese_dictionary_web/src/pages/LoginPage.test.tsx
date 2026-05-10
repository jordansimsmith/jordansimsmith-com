import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { LoginPage } from './LoginPage';
import { getSession } from '../auth/session';

function renderLoginPage() {
  return render(
    <MantineProvider>
      <MemoryRouter initialEntries={['/login']}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/" element={<div>Home page</div>} />
        </Routes>
      </MemoryRouter>
    </MantineProvider>,
  );
}

describe('LoginPage', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    cleanup();
  });

  it('renders login form', () => {
    renderLoginPage();

    expect(
      screen.getByRole('heading', { name: /japanese dictionary/i }),
    ).toBeDefined();
    expect(screen.getByLabelText(/username/i)).toBeDefined();
    expect(screen.getByLabelText(/password/i)).toBeDefined();
    expect(screen.getByRole('button', { name: /log in/i })).toBeDefined();
  });

  it('shows validation errors when fields are empty', async () => {
    const user = userEvent.setup();
    renderLoginPage();

    await user.click(screen.getByRole('button', { name: /log in/i }));

    await waitFor(() => {
      expect(screen.getByText(/username is required/i)).toBeDefined();
    });
    expect(screen.getByText(/password is required/i)).toBeDefined();
  });

  it('writes session and navigates to home on submit', async () => {
    const user = userEvent.setup();
    renderLoginPage();

    await user.type(screen.getByLabelText(/username/i), 'alice');
    await user.type(screen.getByLabelText(/password/i), 'hunter2');
    await user.click(screen.getByRole('button', { name: /log in/i }));

    await waitFor(() => {
      expect(screen.getByText('Home page')).toBeDefined();
    });
    const session = getSession();
    expect(session).toEqual({
      username: 'alice',
      token: btoa('alice:hunter2'),
    });
  });
});
