import {
  render,
  screen,
  cleanup,
  act,
  fireEvent,
} from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { PublishWidget } from './PublishWidget';
import * as clientModule from '../api/client';
import type { PublishResponse } from '../api/client';

function publishResponse(
  overrides: Partial<PublishResponse> = {},
): PublishResponse {
  return {
    status: null,
    published_sku_count: 0,
    total_sku_count: 0,
    error: null,
    started_at: null,
    finished_at: null,
    pending_sku_count: 7,
    ...overrides,
  };
}

function runningResponse(publishedSkuCount: number): PublishResponse {
  return publishResponse({
    status: 'running',
    published_sku_count: publishedSkuCount,
    total_sku_count: 3,
    started_at: 1765420900,
    pending_sku_count: 3 - publishedSkuCount,
  });
}

const succeededResponse = publishResponse({
  status: 'succeeded',
  published_sku_count: 3,
  total_sku_count: 3,
  started_at: 1765420900,
  finished_at: 1765420932,
  pending_sku_count: 0,
});

function renderPublishWidget() {
  return render(
    <MantineProvider>
      <Notifications />
      <PublishWidget />
    </MantineProvider>,
  );
}

function publishButton(): HTMLButtonElement | null {
  return screen.queryByRole('button', {
    name: /Publish/,
  }) as HTMLButtonElement | null;
}

describe('PublishWidget', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it('renders the pending publish count without polling when idle', async () => {
    vi.useFakeTimers();
    const getPublishMock = vi
      .spyOn(clientModule.apiClient, 'getPublish')
      .mockResolvedValue(publishResponse());

    renderPublishWidget();
    await act(async () => {});

    expect(screen.getByText('7')).toBeDefined();
    expect(publishButton()?.disabled).toBe(false);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(6000);
    });
    expect(getPublishMock).toHaveBeenCalledTimes(1);
  });

  it('keeps the trigger enabled without a badge when nothing is pending', async () => {
    vi.spyOn(clientModule.apiClient, 'getPublish').mockResolvedValue(
      publishResponse({ pending_sku_count: 0 }),
    );

    renderPublishWidget();
    await act(async () => {});

    // a zero-pending run still syncs orders, so the trigger stays available
    expect(publishButton()?.disabled).toBe(false);
    expect(screen.queryByText('0')).toBeNull();
  });

  it('triggers a run, polls progress in place of the button, and shows the outcome', async () => {
    vi.useFakeTimers();
    const getPublishMock = vi
      .spyOn(clientModule.apiClient, 'getPublish')
      .mockResolvedValueOnce(publishResponse())
      .mockResolvedValueOnce(runningResponse(1))
      .mockResolvedValue(succeededResponse);
    const createPublishMock = vi
      .spyOn(clientModule.apiClient, 'createPublish')
      .mockResolvedValue(runningResponse(0));

    renderPublishWidget();
    await act(async () => {});

    fireEvent.click(publishButton()!);
    await act(async () => {});

    expect(createPublishMock).toHaveBeenCalledTimes(1);
    expect(screen.getByText('Publishing 1 of 3')).toBeDefined();
    expect(publishButton()).toBeNull();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });

    expect(screen.getByText(/Last publish succeeded/)).toBeDefined();
    expect(publishButton()?.disabled).toBe(false);
    expect(screen.queryByText('0')).toBeNull();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(6000);
    });
    expect(getPublishMock).toHaveBeenCalledTimes(3);
  });

  it('resumes polling an already running publish on load', async () => {
    vi.useFakeTimers();
    const getPublishMock = vi
      .spyOn(clientModule.apiClient, 'getPublish')
      .mockResolvedValueOnce(runningResponse(2))
      .mockResolvedValue(succeededResponse);

    renderPublishWidget();
    await act(async () => {});

    expect(screen.getByText('Publishing 2 of 3')).toBeDefined();
    expect(publishButton()).toBeNull();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(screen.getByText(/Last publish succeeded/)).toBeDefined();
    expect(getPublishMock).toHaveBeenCalledTimes(2);
  });

  it('shows the failed outcome inline without polling', async () => {
    vi.useFakeTimers();
    const getPublishMock = vi
      .spyOn(clientModule.apiClient, 'getPublish')
      .mockResolvedValue(
        publishResponse({
          status: 'failed',
          error: 'FetchTCG authentication failed',
          total_sku_count: 5,
          pending_sku_count: 5,
          started_at: 1765420900,
          finished_at: 1765420910,
        }),
      );

    renderPublishWidget();
    await act(async () => {});

    expect(
      screen.getByText('Publish failed: FetchTCG authentication failed'),
    ).toBeDefined();
    expect(publishButton()?.disabled).toBe(false);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(6000);
    });
    expect(getPublishMock).toHaveBeenCalledTimes(1);
  });
});
