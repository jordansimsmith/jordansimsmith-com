import { useEffect, useRef, useState } from 'react';
import type { RefObject } from 'react';

const PENDING_G_TIMEOUT_MS = 500;

interface ListNavigationOptions {
  itemCount: number;
  onOpen: (index: number) => void;
  searchInputRef: RefObject<HTMLInputElement | null>;
}

interface ListNavigation {
  selectedIndex: number;
  setSelectedIndex: (index: number) => void;
}

export function useListNavigation({
  itemCount,
  onOpen,
  searchInputRef,
}: ListNavigationOptions): ListNavigation {
  const [selectedIndex, setSelectedIndex] = useState(0);
  const pendingG = useRef(false);
  const pendingGTimeout = useRef<ReturnType<typeof setTimeout> | undefined>(
    undefined,
  );

  useEffect(() => {
    setSelectedIndex((current) =>
      Math.min(current, Math.max(itemCount - 1, 0)),
    );
  }, [itemCount]);

  useEffect(() => {
    const clearPendingG = () => {
      pendingG.current = false;
      clearTimeout(pendingGTimeout.current);
    };

    const handleKeyDown = (event: KeyboardEvent) => {
      const target = event.target;
      const searchInput = searchInputRef.current;

      if (searchInput && target === searchInput) {
        if (event.key === 'Escape' || event.key === 'Enter') {
          event.preventDefault();
          searchInput.blur();
        }
        return;
      }

      if (
        target instanceof HTMLInputElement ||
        target instanceof HTMLTextAreaElement ||
        target instanceof HTMLSelectElement ||
        (target instanceof HTMLElement && target.isContentEditable)
      ) {
        return;
      }

      if (event.metaKey || event.ctrlKey || event.altKey) {
        return;
      }

      const wasPendingG = pendingG.current;
      clearPendingG();

      switch (event.key) {
        case 'j':
          setSelectedIndex((current) =>
            Math.min(current + 1, Math.max(itemCount - 1, 0)),
          );
          break;
        case 'k':
          setSelectedIndex((current) => Math.max(current - 1, 0));
          break;
        case 'g':
          if (wasPendingG) {
            setSelectedIndex(0);
          } else {
            pendingG.current = true;
            pendingGTimeout.current = setTimeout(
              clearPendingG,
              PENDING_G_TIMEOUT_MS,
            );
          }
          break;
        case 'G':
          setSelectedIndex(Math.max(itemCount - 1, 0));
          break;
        case '/':
          event.preventDefault();
          searchInput?.focus();
          searchInput?.select();
          break;
        case 'Enter':
          if (itemCount > 0) {
            onOpen(selectedIndex);
          }
          break;
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
      clearTimeout(pendingGTimeout.current);
    };
  }, [itemCount, onOpen, searchInputRef, selectedIndex]);

  return { selectedIndex, setSelectedIndex };
}
