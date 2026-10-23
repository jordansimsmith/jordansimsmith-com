import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../api/client';
import { PricingPage } from './PricingPage';

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
  });

  it('loads the default price floor', async () => {
    renderPricingPage();

    expect(await screen.findByLabelText('Price floor (NZD)')).toHaveProperty(
      'value',
      '0.25',
    );
    expect(
      screen.getByText(
        'Minimum suggested price for future appraisals. Defaults to NZ$0.25.',
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
});
