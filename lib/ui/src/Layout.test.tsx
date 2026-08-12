import { render, screen, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { Layout } from './Layout';

function renderLayout(props: {
  appTitle?: string;
  username?: string | null;
  onLogout?: () => void;
  navbar?: React.ReactNode;
  navbarOpened?: boolean;
  onToggleNavbar?: () => void;
}) {
  return render(
    <MantineProvider>
      <Layout
        appTitle={props.appTitle ?? 'Test app'}
        username={props.username ?? null}
        onLogout={props.onLogout ?? (() => {})}
        navbar={props.navbar}
        navbarOpened={props.navbarOpened}
        onToggleNavbar={props.onToggleNavbar}
      >
        <div>page content</div>
      </Layout>
    </MantineProvider>,
  );
}

describe('Layout', () => {
  afterEach(() => {
    cleanup();
  });

  it('renders the app title and children', () => {
    renderLayout({ appTitle: 'My app' });

    expect(screen.getByRole('heading', { name: /my app/i })).toBeDefined();
    expect(screen.getByText('page content')).toBeDefined();
  });

  it('shows the username and log out button when a username is provided', () => {
    renderLayout({ username: 'alice' });

    expect(screen.getByText('alice')).toBeDefined();
    expect(screen.getByRole('button', { name: /log out/i })).toBeDefined();
  });

  it('hides the username and log out button when username is null', () => {
    renderLayout({ username: null });

    expect(screen.queryByRole('button', { name: /log out/i })).toBeNull();
  });

  it('invokes onLogout when the log out button is clicked', async () => {
    const onLogout = vi.fn();
    const user = userEvent.setup();
    renderLayout({ username: 'alice', onLogout });

    await user.click(screen.getByRole('button', { name: /log out/i }));

    expect(onLogout).toHaveBeenCalledTimes(1);
  });

  it('renders navbar content when a navbar is provided', () => {
    renderLayout({ navbar: <div>nav content</div> });

    expect(screen.getByText('nav content')).toBeDefined();
  });

  it('hides the navbar and burger when no navbar is provided', () => {
    renderLayout({});

    expect(
      screen.queryByRole('button', { name: /toggle navigation/i }),
    ).toBeNull();
  });

  it('invokes onToggleNavbar when the burger is clicked', async () => {
    const onToggleNavbar = vi.fn();
    const user = userEvent.setup();
    renderLayout({ navbar: <div>nav content</div>, onToggleNavbar });

    await user.click(
      screen.getByRole('button', { name: /toggle navigation/i }),
    );

    expect(onToggleNavbar).toHaveBeenCalledTimes(1);
  });
});
