import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../api/client';
import { PricingPage } from './PricingPage';
import type { RepriceResponse } from '../api/client';

function renderPricingPage() {
  return render(
    <MantineProvider>
      <MemoryRouter>
        <PricingPage />
      </MemoryRouter>
    </MantineProvider>,
  );
}

describe('PricingPage', () => {
  beforeEach(() => {
    vi.spyOn(apiClient, 'getReprice').mockResolvedValue(null);
    vi.spyOn(apiClient, 'createReprice').mockResolvedValue(undefined);
    vi.spyOn(apiClient, 'getPricing').mockResolvedValue({
      price_floor: '0.25',
    });
    vi.spyOn(apiClient, 'updatePricing').mockResolvedValue({
      price_floor: '0.25',
    });
    vi.spyOn(notifications, 'show');
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('loads the default price floor', async () => {
    renderPricingPage();

    expect(await screen.findByLabelText('Price floor (NZD)')).toHaveProperty(
      'value',
      '0.25',
    );
    expect(
      screen.getByText(
        'The lowest price used for new appraisals and inventory repricing.',
      ),
    ).toBeDefined();
  });

  it('loads a saved price floor', async () => {
    vi.mocked(apiClient.getPricing).mockResolvedValue({ price_floor: '1.50' });
    renderPricingPage();

    expect(await screen.findByLabelText('Price floor (NZD)')).toHaveProperty(
      'value',
      '1.50',
    );
  });

  it('saves the string amount and adopts the normalized response', async () => {
    const user = userEvent.setup();
    vi.mocked(apiClient.updatePricing).mockResolvedValue({
      price_floor: '1.20',
    });
    renderPricingPage();

    const input = await screen.findByLabelText('Price floor (NZD)');
    await user.clear(input);
    await user.type(input, '1.2');
    await user.click(screen.getByRole('button', { name: 'Save price floor' }));

    await waitFor(() => {
      expect(apiClient.updatePricing).toHaveBeenCalledWith({
        price_floor: '1.2',
      });
      expect(input).toHaveProperty('value', '1.20');
    });
    expect(notifications.show).toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Pricing saved', color: 'green' }),
    );
  });

  it('disables saving when the amount is unchanged or empty', async () => {
    const user = userEvent.setup();
    renderPricingPage();

    const input = await screen.findByLabelText('Price floor (NZD)');
    const save = screen.getByRole('button', { name: 'Save price floor' });
    expect(save).toHaveProperty('disabled', true);

    await user.clear(input);
    expect(save).toHaveProperty('disabled', true);
    await user.type(input, '1.00');
    expect(save).toHaveProperty('disabled', false);
    await user.clear(input);
    expect(save).toHaveProperty('disabled', true);
    expect(apiClient.updatePricing).not.toHaveBeenCalled();
  });

  it('preserves the amount when saving fails', async () => {
    const user = userEvent.setup();
    vi.mocked(apiClient.updatePricing).mockRejectedValue(
      new Error('Save failed'),
    );
    renderPricingPage();

    const input = await screen.findByLabelText('Price floor (NZD)');
    await user.clear(input);
    await user.type(input, '2.75');
    await user.click(screen.getByRole('button', { name: 'Save price floor' }));

    await waitFor(() => {
      expect(input).toHaveProperty('value', '2.75');
      expect(notifications.show).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Error', message: 'Save failed' }),
      );
    });
  });

  it('shows active-job conflicts from the API', async () => {
    const user = userEvent.setup();
    vi.mocked(apiClient.updatePricing).mockRejectedValue(
      new Error('Pricing cannot be updated while a job is queued or running'),
    );
    renderPricingPage();

    const input = await screen.findByLabelText('Price floor (NZD)');
    await user.clear(input);
    await user.type(input, '1.25');
    await user.click(screen.getByRole('button', { name: 'Save price floor' }));

    await waitFor(() => {
      expect(notifications.show).toHaveBeenCalledWith(
        expect.objectContaining({
          title: 'Error',
          message: 'Pricing cannot be updated while a job is queued or running',
        }),
      );
    });
  });

  it('shows a load error without rendering a default field', async () => {
    vi.mocked(apiClient.getPricing).mockRejectedValue(
      new Error('Network error'),
    );
    renderPricingPage();

    expect(
      await screen.findByText('Failed to load pricing settings: Network error'),
    ).toBeDefined();
    expect(screen.getByRole('alert')).toBeDefined();
    expect(screen.queryByLabelText('Price floor (NZD)')).toBeNull();
  });
  function run(overrides: Partial<RepriceResponse> = {}): RepriceResponse {
    return {
      job_id: 'reprice-1',
      status: 'running',
      processed_sku_count: 100,
      error: null,
      started_at: 1765420900,
      finished_at: null,
      ...overrides,
    };
  }

  it('requires saved changes before repricing and reads the accepted run', async () => {
    const user = userEvent.setup();
    renderPricingPage();
    const input = await screen.findByLabelText('Price floor (NZD)');
    const start = screen.getByRole('button', { name: 'Reprice inventory' });
    expect(await screen.findByText('Ready to reprice inventory')).toBeDefined();
    await user.clear(input);
    await user.type(input, '1.2');
    expect(start).toHaveProperty('disabled', true);
    expect(
      screen.getByText(
        'Save your price floor before starting a repricing run.',
      ),
    ).toBeDefined();
    vi.mocked(apiClient.updatePricing).mockResolvedValue({
      price_floor: '1.20',
    });
    await user.click(screen.getByRole('button', { name: 'Save price floor' }));
    await waitFor(() => expect(start).toHaveProperty('disabled', false));
    vi.mocked(apiClient.getReprice).mockResolvedValue(
      run({ status: 'queued', processed_sku_count: 0 }),
    );
    await user.click(start);
    expect(apiClient.createReprice).toHaveBeenCalledOnce();
    expect(await screen.findByText('Repricing queued')).toBeDefined();
    expect(input).toHaveProperty('disabled', true);
    expect(
      screen.getByRole('button', { name: 'Save price floor' }),
    ).toHaveProperty('disabled', true);
  });

  it('polls active runs, retains progress on read errors, and stops on completion', async () => {
    vi.useFakeTimers();
    vi.mocked(apiClient.getReprice).mockResolvedValue(run());
    renderPricingPage();
    await act(async () => {});
    expect(screen.getByText('100 SKUs checked')).toBeDefined();
    vi.mocked(apiClient.getReprice).mockRejectedValueOnce(
      new Error('Connection lost'),
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(screen.getByText('100 SKUs checked')).toBeDefined();
    expect(screen.getByText('Connection lost')).toBeDefined();
    vi.mocked(apiClient.getReprice).mockResolvedValue(
      run({ processed_sku_count: 200 }),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Retry status' }));
    await act(async () => {});
    expect(screen.getByText('200 SKUs checked')).toBeDefined();
    expect(screen.queryByText('Connection lost')).toBeNull();
    vi.mocked(apiClient.getReprice).mockResolvedValue(
      run({
        status: 'succeeded',
        processed_sku_count: 201,
        finished_at: 1765420932,
      }),
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(screen.getByText('Repricing complete')).toBeDefined();
    const calls = vi.mocked(apiClient.getReprice).mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(6000);
    });
    expect(apiClient.getReprice).toHaveBeenCalledTimes(calls);
  });

  it('restores on focus without losing edits and cleans up polling on unmount', async () => {
    vi.useFakeTimers();
    renderPricingPage();
    await act(async () => {});
    fireEvent.change(screen.getByLabelText('Price floor (NZD)'), {
      target: { value: '1.25' },
    });
    vi.mocked(apiClient.getReprice).mockResolvedValue(run());
    fireEvent(window, new Event('focus'));
    await act(async () => {});
    expect(screen.getByText('Repricing inventory')).toBeDefined();
    expect(screen.getByLabelText('Price floor (NZD)')).toHaveProperty(
      'value',
      '1.25',
    );
    cleanup();
    const calls = vi.mocked(apiClient.getReprice).mock.calls.length;
    fireEvent(window, new Event('focus'));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4000);
    });
    expect(apiClient.getReprice).toHaveBeenCalledTimes(calls);
  });

  it('shows an empty completed run and a failed run with actionable feedback', async () => {
    vi.mocked(apiClient.getReprice).mockResolvedValue(
      run({
        status: 'succeeded',
        processed_sku_count: 0,
        finished_at: 1765420932,
      }),
    );
    renderPricingPage();
    expect(
      await screen.findByText('No inventory needed repricing.'),
    ).toBeDefined();
    vi.mocked(apiClient.getReprice).mockResolvedValue(
      run({
        status: 'failed',
        error: 'Update your FetchTCG credentials',
        finished_at: 1765420932,
      }),
    );
    fireEvent(window, new Event('focus'));
    expect(await screen.findByText('Repricing failed')).toBeDefined();
    expect(screen.getByText('Update your FetchTCG credentials')).toBeDefined();
    expect(
      screen.getByText(
        '100 SKUs checked before the run stopped. Price changes already made remain ready to publish.',
      ),
    ).toBeDefined();
    expect(
      screen.getByRole('button', { name: 'Reprice inventory' }),
    ).toHaveProperty('disabled', false);
  });

  it('surfaces a start conflict and restores the current run', async () => {
    const user = userEvent.setup();
    renderPricingPage();
    await screen.findByText('Ready to reprice inventory');
    vi.mocked(apiClient.createReprice).mockRejectedValue(
      new Error('Another job is active'),
    );
    vi.mocked(apiClient.getReprice).mockResolvedValue(run());
    await user.click(screen.getByRole('button', { name: 'Reprice inventory' }));
    expect(notifications.show).toHaveBeenCalledWith(
      expect.objectContaining({ message: 'Another job is active' }),
    );
    expect(await screen.findByText('Repricing inventory')).toBeDefined();
  });

  it('keeps an accepted run locked and polling when its status read fails', async () => {
    const user = userEvent.setup();
    renderPricingPage();
    await screen.findByText('Ready to reprice inventory');
    vi.mocked(apiClient.getReprice).mockRejectedValueOnce(
      new Error('Status offline'),
    );
    await user.click(screen.getByRole('button', { name: 'Reprice inventory' }));
    expect(await screen.findByText('Status offline')).toBeDefined();
    expect(screen.getByLabelText('Price floor (NZD)')).toHaveProperty(
      'disabled',
      true,
    );
    expect(screen.getByText('Repricing queued')).toBeDefined();
    vi.mocked(apiClient.getReprice).mockResolvedValue(run());
    await user.click(screen.getByRole('button', { name: 'Retry status' }));
    expect(await screen.findByText('100 SKUs checked')).toBeDefined();
  });

  it('offers retry when the initial status read fails', async () => {
    const user = userEvent.setup();
    vi.mocked(apiClient.getReprice).mockRejectedValueOnce(
      new Error('Status offline'),
    );
    renderPricingPage();
    expect(await screen.findByText('Status offline')).toBeDefined();
    expect(
      screen.getByRole('button', { name: 'Reprice inventory' }),
    ).toHaveProperty('disabled', true);
    await user.click(screen.getByRole('button', { name: 'Retry status' }));
    expect(await screen.findByText('Ready to reprice inventory')).toBeDefined();
    expect(
      screen.getByRole('button', { name: 'Reprice inventory' }),
    ).toHaveProperty('disabled', false);
  });
});
