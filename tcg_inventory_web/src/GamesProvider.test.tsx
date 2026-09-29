import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { StrictMode } from 'react';
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './api/client';
import type { Game } from './api/client';
import { GamesProvider, useGames } from './GamesProvider';

const TEST_GAMES: Game[] = [
  {
    id: 'mtg',
    display_name: 'Magic: The Gathering',
    scanning_enabled: true,
    csv_import_enabled: true,
    finishes: [
      { id: 'normal', display_name: 'Normal' },
      { id: 'foil', display_name: 'Foil' },
      { id: 'etched', display_name: 'Etched' },
    ],
  },
];

function GameRegistryLabel() {
  const { games } = useGames();
  return <p>{games[0].display_name}</p>;
}

function renderWorkspace() {
  return render(
    <StrictMode>
      <MantineProvider>
        <MemoryRouter initialEntries={['/inventory']}>
          <GamesProvider>
            <nav>
              <Link to="/inventory">Inventory</Link>
              <Link to="/reports">Reports</Link>
            </nav>
            <Routes>
              <Route
                path="/inventory"
                element={
                  <>
                    <h1>Inventory route</h1>
                    <GameRegistryLabel />
                  </>
                }
              />
              <Route
                path="/reports"
                element={
                  <>
                    <h1>Reports route</h1>
                    <GameRegistryLabel />
                  </>
                }
              />
            </Routes>
          </GamesProvider>
        </MemoryRouter>
      </MantineProvider>
    </StrictMode>,
  );
}

function expectWorkspaceBlank() {
  expect(screen.queryByRole('heading', { name: 'Inventory route' })).toBeNull();
  expect(screen.queryByRole('link', { name: 'Inventory' })).toBeNull();
}

describe('GamesProvider', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('loads once across route changes and reloads after the provider remounts', async () => {
    const getGames = vi
      .spyOn(apiClient, 'getGames')
      .mockResolvedValue({ games: TEST_GAMES });

    const user = userEvent.setup();
    const workspace = renderWorkspace();

    expectWorkspaceBlank();
    expect(await screen.findByText('Magic: The Gathering')).toBeDefined();
    expect(getGames).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('link', { name: 'Reports' }));
    expect(
      screen.getByRole('heading', { name: 'Reports route' }),
    ).toBeDefined();
    expect(getGames).toHaveBeenCalledTimes(1);

    workspace.unmount();
    renderWorkspace();
    expect(await screen.findByText('Magic: The Gathering')).toBeDefined();
    expect(getGames).toHaveBeenCalledTimes(2);
  });

  it('keeps the workspace blank when metadata loading fails', async () => {
    const getGames = vi
      .spyOn(apiClient, 'getGames')
      .mockRejectedValue(new Error('registry unavailable'));
    renderWorkspace();

    await waitFor(() => expect(getGames).toHaveBeenCalledTimes(1));
    expectWorkspaceBlank();
  });

  it('keeps the workspace blank when the registry is empty', async () => {
    const getGames = vi
      .spyOn(apiClient, 'getGames')
      .mockResolvedValue({ games: [] });

    renderWorkspace();

    await waitFor(() => expect(getGames).toHaveBeenCalledTimes(1));
    expectWorkspaceBlank();
  });
});
