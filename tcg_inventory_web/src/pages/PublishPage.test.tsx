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

  it('keeps the dot hidden and offers publishing before the first run', async () => {
    vi.spyOn(clientModule.apiClient, 'getPublish').mockRejectedValue(
      new Error('Not Found'),
    );

    renderPublishPage();

    expect(await screen.findByText('No previous publish run')).toBeDefined();
    expect(screen.getByText('Never')).toBeDefined();
    expect(
      (
        screen.getByRole('button', {
          name: 'Publish changes',
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(false);
    expect(publishLink().getAttribute('aria-current')).toBe('page');
  });

  it('shows the clean summary and no attention dot when nothing is pending', async () => {
    vi.spyOn(clientModule.apiClient, 'getPublish').mockResolvedValue(
      publishResponse(),
    );

    renderPublishPage();

    expect(await screen.findByText('Inventory is up to date')).toBeDefined();
    expect(screen.getByText('Last successful publish')).toBeDefined();
    expect(publishLink().getAttribute('aria-current')).toBe('page');
    expect(
      screen.queryByRole('link', {
        name: 'Publish, unpublished inventory changes',
      }),
    ).toBeNull();
  });

  it('shows an empty completed run without a published count', async () => {
    vi.spyOn(clientModule.apiClient, 'getPublish').mockResolvedValue(
      publishResponse({ published_sku_count: 0 }),
    );

    renderPublishPage();

    expect(await screen.findByText('Inventory is up to date')).toBeDefined();
    expect(
      screen.getByText('No inventory changes needed publishing.'),
    ).toBeDefined();
  });

  it('shows remaining SKUs and polls through zero until the run completes', async () => {
    vi.useFakeTimers();
    const getPublishMock = vi
      .spyOn(clientModule.apiClient, 'getPublish')
      .mockResolvedValueOnce(
        publishResponse({
          status: 'running',
          finished_at: null,
          published_sku_count: 1,
          pending_sku_count: 2,
        }),
      )
      .mockResolvedValueOnce(
        publishResponse({
          status: 'running',
          finished_at: null,
          published_sku_count: 3,
          pending_sku_count: 0,
        }),
      )
      .mockResolvedValueOnce(publishResponse());

    renderPublishPage();

    await act(async () => {});
    expect(screen.getByText('2 SKUs remaining to publish')).toBeDefined();
    expect(screen.getByRole('progressbar')).toBeDefined();
    expect(screen.queryByText('Unavailable')).toBeNull();
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

    expect(screen.getByText('0 SKUs remaining to publish')).toBeDefined();
    expect(screen.getByRole('progressbar')).toBeDefined();
    expect(getPublishMock).toHaveBeenCalledTimes(2);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });

    expect(screen.getByText('Inventory is up to date')).toBeDefined();
    expect(getPublishMock).toHaveBeenCalledTimes(3);
    expect(screen.queryByText(/remaining to publish/)).toBeNull();
  });

  it('shows a queued run without showing a completed progress bar', async () => {
    vi.spyOn(clientModule.apiClient, 'getPublish').mockResolvedValue(
      publishResponse({
        status: 'queued',
        started_at: null,
        finished_at: null,
        pending_sku_count: 3,
      }),
    );

    renderPublishPage();

    expect(await screen.findByText('Publish run queued')).toBeDefined();
    expect(
      screen.getByText('Waiting for the publish run to start'),
    ).toBeDefined();
    expect(screen.getByText('Current run')).toBeDefined();
    expect(screen.queryByRole('progressbar')).toBeNull();
    expect(
      (screen.getByRole('button', { name: 'Publishing' }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
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
    expect(screen.getByText('0 SKUs remaining to publish')).toBeDefined();
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

    expect(
      await screen.findByText('5 SKUs still need publishing'),
    ).toBeDefined();
    expect(screen.getByText('Publish failed')).toBeDefined();
    expect(screen.getByText('FetchTCG authentication failed')).toBeDefined();
    expect(publishLink('Publish, unpublished inventory changes')).toBeDefined();

    fireEvent.click(screen.getByRole('button', { name: 'Publish changes' }));
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

  it('keeps publishing disabled and retries a failed status read', async () => {
    vi.spyOn(clientModule.apiClient, 'getPublish')
      .mockRejectedValueOnce(new Error('Service unavailable'))
      .mockResolvedValueOnce(publishResponse({ pending_sku_count: 2 }));

    renderPublishPage();

    expect(
      await screen.findByText('Publish status needs attention'),
    ).toBeDefined();
    expect(screen.getByText('Service unavailable')).toBeDefined();
    expect(
      (
        screen.getByRole('button', {
          name: 'Publish changes',
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: 'Retry status' }));

    expect(await screen.findByText('2 SKUs ready to publish')).toBeDefined();
    expect(
      (
        screen.getByRole('button', {
          name: 'Publish changes',
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(false);
  });
});
