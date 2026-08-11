import { render, screen, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useRef } from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { useListNavigation } from './use-list-navigation';

function Harness({
  items,
  onOpen,
}: {
  items: string[];
  onOpen: (index: number) => void;
}) {
  const searchInputRef = useRef<HTMLInputElement>(null);
  const { selectedIndex } = useListNavigation({
    itemCount: items.length,
    onOpen,
    searchInputRef,
  });

  return (
    <div>
      <input ref={searchInputRef} aria-label="search" />
      <ul>
        {items.map((item, index) => (
          <li key={item} data-selected={index === selectedIndex}>
            {item}
          </li>
        ))}
      </ul>
    </div>
  );
}

const items = ['alpha', 'beta', 'gamma', 'delta'];

function selectedItem() {
  return document.querySelector('[data-selected="true"]')?.textContent;
}

describe('useListNavigation', () => {
  afterEach(() => {
    cleanup();
  });

  it('moves selection down with j and up with k', async () => {
    const user = userEvent.setup();
    render(<Harness items={items} onOpen={vi.fn()} />);

    expect(selectedItem()).toBe('alpha');

    await user.keyboard('j');
    expect(selectedItem()).toBe('beta');

    await user.keyboard('j');
    expect(selectedItem()).toBe('gamma');

    await user.keyboard('k');
    expect(selectedItem()).toBe('beta');
  });

  it('clamps selection at both ends of the list', async () => {
    const user = userEvent.setup();
    render(<Harness items={items} onOpen={vi.fn()} />);

    await user.keyboard('k');
    expect(selectedItem()).toBe('alpha');

    await user.keyboard('G');
    await user.keyboard('j');
    expect(selectedItem()).toBe('delta');
  });

  it('jumps to the first row with gg and the last row with G', async () => {
    const user = userEvent.setup();
    render(<Harness items={items} onOpen={vi.fn()} />);

    await user.keyboard('G');
    expect(selectedItem()).toBe('delta');

    await user.keyboard('gg');
    expect(selectedItem()).toBe('alpha');
  });

  it('does not jump when g is followed by another key', async () => {
    const user = userEvent.setup();
    render(<Harness items={items} onOpen={vi.fn()} />);

    await user.keyboard('G');
    await user.keyboard('gj');
    expect(selectedItem()).toBe('delta');
  });

  it('focuses the search input with / without typing a slash', async () => {
    const user = userEvent.setup();
    render(<Harness items={items} onOpen={vi.fn()} />);

    await user.keyboard('/');
    const search = screen.getByLabelText('search');
    expect(document.activeElement).toBe(search);

    await user.keyboard('sol');
    expect(search).toHaveProperty('value', 'sol');
  });

  it('does not navigate while typing in the search input', async () => {
    const user = userEvent.setup();
    render(<Harness items={items} onOpen={vi.fn()} />);

    await user.keyboard('/');
    await user.keyboard('jkg');

    expect(selectedItem()).toBe('alpha');
    expect(screen.getByLabelText('search')).toHaveProperty('value', 'jkg');
  });

  it('returns focus to the list when Enter is pressed in the search input', async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn();
    render(<Harness items={items} onOpen={onOpen} />);

    await user.keyboard('/');
    await user.keyboard('{Enter}');
    expect(document.activeElement).not.toBe(screen.getByLabelText('search'));
    expect(onOpen).not.toHaveBeenCalled();

    await user.keyboard('j');
    expect(selectedItem()).toBe('beta');
  });

  it('blurs the search input with Escape', async () => {
    const user = userEvent.setup();
    render(<Harness items={items} onOpen={vi.fn()} />);

    await user.keyboard('/');
    await user.keyboard('{Escape}');

    expect(document.activeElement).not.toBe(screen.getByLabelText('search'));
  });

  it('opens the selected row with Enter', async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn();
    render(<Harness items={items} onOpen={onOpen} />);

    await user.keyboard('jj');
    await user.keyboard('{Enter}');

    expect(onOpen).toHaveBeenCalledWith(2);
  });

  it('does not open a row when the list is empty', async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn();
    render(<Harness items={[]} onOpen={onOpen} />);

    await user.keyboard('{Enter}');

    expect(onOpen).not.toHaveBeenCalled();
  });

  it('clamps selection when the list shrinks', async () => {
    const user = userEvent.setup();
    const { rerender } = render(<Harness items={items} onOpen={vi.fn()} />);

    await user.keyboard('G');
    expect(selectedItem()).toBe('delta');

    rerender(<Harness items={items.slice(0, 2)} onOpen={vi.fn()} />);
    expect(selectedItem()).toBe('beta');
  });
});
