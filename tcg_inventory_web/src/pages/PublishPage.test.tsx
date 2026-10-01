import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
} from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PublishStatusProvider } from '../PublishStatusProvider';
import * as clientModule from '../api/client';
import type { PublishResponse } from '../api/client';
import { PublishPage } from './PublishPage';

function publishResponse(
  overrides: Partial<PublishResponse> = {},
): PublishResponse {
  return {
    status: 'succeeded',
    published_sku_count: 3,
    total_sku_count: 3,
    error: null,
    started_at: 1765420900,
    finished_at: 1765420932,
    pending_sku_count: 0,
    ...overrides,
  };
}

function renderPublishPage() {
  return render(
    <MantineProvider>
      <Notifications />
      <MemoryRouter initialEntries={['/publish']}>
        <PublishStatusProvider>
          <Routes>
            <Route path="/publish" element={<PublishPage />} />
          </Routes>
        </PublishStatusProvider>
      </MemoryRouter>
    </MantineProvider>,
  );
}

function publishLink(name = 'Publish'): HTMLAnchorElement {
  return screen.getByRole('link', { name }) as HTMLAnchorElement;
}

describe('PublishPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it('keeps the sidebar dot hidden until publish status loads', async () => {
    let resolvePublish!: (response: PublishResponse) => void;
    vi.spyOn(clientModule.apiClient, 'getPublish').mockReturnValue(
      new Promise((resolve) => {
        resolvePublish = resolve;
      }),
    );

    renderPublishPage();

    expect(publishLink().getAttribute('aria-current')).toBe('page');
    expect(
      screen.queryByRole('link', {
        name: 'Publish, unpublished inventory changes',
      }),
    ).toBeNull();

    await act(async () => {
      resolvePublish(publishResponse({ pending_sku_count: 4 }));
    });

    expect(publishLink('Publish, unpublished inventory changes')).toBeDefined();
  });

  it('keeps the dot hidden and offers Publish before the first run', async () => {
    vi.spyOn(clientModule.apiClient, 'getPublish').mockRejectedValue(
      new Error('Not Found'),
    );

    renderPublishPage();

    expect(await screen.findByText('Never')).toBeDefined();
    expect(
      (screen.getByRole('button', { name: 'Publish' }) as HTMLButtonElement)
        .disabled,
    ).toBe(false);
    expect(publishLink().getAttribute('aria-current')).toBe('page');
  });

  it('shows the clean summary and no attention dot when nothing is pending', async () => {
    vi.spyOn(clientModule.apiClient, 'getPublish').mockResolvedValue(
      publishResponse(),
    );

    renderPublishPage();

    expect(await screen.findByText('Pending SKUs: 0')).toBeDefined();
    expect(screen.getByText('Last published')).toBeDefined();
    expect(publishLink().getAttribute('aria-current')).toBe('page');
    expect(
      screen.queryByRole('link', {
        name: 'Publish, unpublished inventory changes',
      }),
    ).toBeNull();
  });

  it('hides the dot while a run is active and polls to completion', async () => {
    vi.useFakeTimers();
    const getPublishMock = vi
      .spyOn(clientModule.apiClient, 'getPublish')
      .mockResolvedValueOnce(
        publishResponse({
          status: 'running',
          finished_at: null,
          published_sku_count: 1,
          total_sku_count: 3,
          pending_sku_count: 2,
        }),
      )
      .mockResolvedValueOnce(publishResponse());

    renderPublishPage();

    await act(async () => {});
    expect(screen.getByText('Publishing 1 of 3 SKUs')).toBeDefined();
    expect(
      (screen.getByRole('button', { name: 'Publishing' }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
    expect(
      screen.queryByRole('link', {
        name: 'Publish, unpublished inventory changes',
      }),
    ).toBeNull();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });

    expect(screen.getByText('Pending SKUs: 0')).toBeDefined();
    expect(getPublishMock).toHaveBeenCalledTimes(2);
    expect(screen.queryByText(/Publishing 1 of 3 SKUs/)).toBeNull();
  });

  it('stops polling after leaving the Publish page', async () => {
    vi.useFakeTimers();
    const getPublishMock = vi
      .spyOn(clientModule.apiClient, 'getPublish')
      .mockResolvedValue(
        publishResponse({ status: 'running', finished_at: null }),
      );

    render(
      <MantineProvider>
        <Notifications />
        <MemoryRouter initialEntries={['/publish']}>
          <PublishStatusProvider>
            <Routes>
              <Route path="/publish" element={<PublishPage />} />
              <Route path="/inventory" element={<div>Inventory page</div>} />
            </Routes>
          </PublishStatusProvider>
        </MemoryRouter>
      </MantineProvider>,
    );

    await act(async () => {});
    expect(screen.getByText('Publishing 3 of 3 SKUs')).toBeDefined();
    expect(getPublishMock).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('link', { name: 'Inventory' }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(6000);
    });

    expect(screen.getByText('Inventory page')).toBeDefined();
    expect(getPublishMock).toHaveBeenCalledTimes(1);
  });

  it('shows the failed run, marks pending work, and allows another publish', async () => {
    const getPublishMock = vi
      .spyOn(clientModule.apiClient, 'getPublish')
      .mockResolvedValue(
        publishResponse({
          status: 'failed',
          error: 'FetchTCG authentication failed',
          pending_sku_count: 5,
        }),
      );
    const createPublishMock = vi
      .spyOn(clientModule.apiClient, 'createPublish')
      .mockResolvedValue(undefined);

    renderPublishPage();

    expect(await screen.findByText('Publish failed')).toBeDefined();
    expect(screen.getByText('FetchTCG authentication failed')).toBeDefined();
    expect(publishLink('Publish, unpublished inventory changes')).toBeDefined();

    fireEvent.click(screen.getByRole('button', { name: 'Publish' }));
    await act(async () => {});

    expect(createPublishMock).toHaveBeenCalledTimes(1);
    expect(getPublishMock).toHaveBeenCalledTimes(2);
    expect(
      screen.queryByRole('link', {
        name: 'Publish, unpublished inventory changes',
      }),
    ).toBeNull();
  });

  it('shows the last successful publish time as relative time', async () => {
    vi.setSystemTime(new Date((1765420932 + 3600) * 1000));
    vi.spyOn(clientModule.apiClient, 'getPublish').mockResolvedValue(
      publishResponse(),
    );

    renderPublishPage();

    expect(await screen.findByText('an hour ago')).toBeDefined();
  });
});
