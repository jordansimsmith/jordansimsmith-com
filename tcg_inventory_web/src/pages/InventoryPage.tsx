import { useEffect, useRef, useState } from 'react';
import { Button, Group, Stack, Tabs, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useNavigate } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';
import {
  CollectionLoadingState,
  CollectionMessage,
  CollectionSurface,
} from '../components/CollectionSurface';
import { PageHeader } from '../components/PageHeader';
import { PublishWidget } from '../components/PublishWidget';
import { SkuTable } from '../components/SkuTable';
import { apiClient } from '../api/client';
import type { FindSkusParams, SkuSummary } from '../api/client';
import { GAMES } from '../domain/games';
import type { GameId } from '../domain/games';

function InventoryGameSection({
  game,
  active,
}: {
  game: (typeof GAMES)[number];
  active: boolean;
}) {
  const navigate = useNavigate();
  const [skus, setSkus] = useState<SkuSummary[]>([]);
  const [nextContinuation, setNextContinuation] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const requestIdRef = useRef(0);

  const openSku = (sku: SkuSummary) => {
    navigate(`/inventory/${encodeURIComponent(sku.sku_id)}`);
  };

  useEffect(() => {
    if (!active) {
      return;
    }

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key !== '/' || event.metaKey || event.ctrlKey || event.altKey) {
        return;
      }

      const target = event.target;
      if (
        target instanceof HTMLInputElement ||
        target instanceof HTMLTextAreaElement ||
        target instanceof HTMLSelectElement ||
        (target instanceof HTMLElement &&
          (target.isContentEditable ||
            target.closest('button, a, [role="button"], [role="link"]') !=
              null))
      ) {
        return;
      }

      event.preventDefault();
      searchInputRef.current?.focus();
      searchInputRef.current?.select();
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [active]);

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search), 300);
    return () => clearTimeout(timer);
  }, [search]);

  useEffect(() => {
    if (!active) {
      requestIdRef.current += 1;
      return;
    }

    const requestId = ++requestIdRef.current;
    setLoading(true);
    setNextContinuation(null);
    const fetchSkus = async () => {
      const params: FindSkusParams = {
        game: game.id,
        search: debouncedSearch || undefined,
      };
      try {
        const response = await apiClient.findSkus(params);
        if (requestId !== requestIdRef.current) {
          return;
        }
        setSkus(response.skus);
        setNextContinuation(response.next_continuation);
        setError(null);
      } catch (e) {
        if (requestId !== requestIdRef.current) {
          return;
        }
        const message = e instanceof Error ? e.message : 'Failed to load SKUs';
        setError(message);
        notifications.show({ title: 'Error', message, color: 'red' });
      } finally {
        if (requestId === requestIdRef.current) {
          setLoading(false);
        }
      }
    };

    fetchSkus();
  }, [active, debouncedSearch, game.id]);

  const handleLoadMore = async () => {
    if (!nextContinuation) {
      return;
    }
    setLoadingMore(true);
    try {
      const response = await apiClient.findSkus({
        game: game.id,
        search: debouncedSearch || undefined,
        continuation: nextContinuation,
      });
      setSkus((previous) => [...previous, ...response.skus]);
      setNextContinuation(response.next_continuation);
    } catch (e) {
      const message =
        e instanceof Error ? e.message : 'Failed to load more SKUs';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setLoadingMore(false);
    }
  };

  return (
    <CollectionSurface
      ariaLabel={`${game.label} inventory`}
      toolbar={
        <TextInput
          ref={searchInputRef}
          value={search}
          onChange={(event) => setSearch(event.currentTarget.value)}
          label={`Search ${game.label} inventory`}
          placeholder="Card name, / to focus"
          maw={400}
        />
      }
      footer={
        nextContinuation ? (
          <Group justify="flex-start">
            <Button
              variant="default"
              onClick={handleLoadMore}
              loading={loadingMore}
            >
              Load more
            </Button>
          </Group>
        ) : undefined
      }
    >
      {loading && <CollectionLoadingState />}
      {!loading && error && (
        <CollectionMessage
          title="Inventory could not be loaded"
          description={error}
          tone="error"
        />
      )}
      {!loading && !error && skus.length === 0 && (
        <CollectionMessage
          title="No SKUs found."
          description="Try a different card name."
        />
      )}
      {!loading && !error && skus.length > 0 && (
        <SkuTable skus={skus} onOpen={openSku} />
      )}
    </CollectionSurface>
  );
}

export function InventoryPage() {
  const [activeGame, setActiveGame] = useState<GameId>(GAMES[0].id);

  return (
    <AppShellLayout>
      <Stack gap="lg">
        <PageHeader
          title="Inventory"
          description="Find stock by card name and open a printing to view its units and locations."
          actions={<PublishWidget />}
        />
        <Tabs
          value={activeGame}
          onChange={(value) => {
            if (value) {
              setActiveGame(value as GameId);
            }
          }}
        >
          <Tabs.List aria-label="Inventory game">
            {GAMES.map((game) => (
              <Tabs.Tab key={game.id} value={game.id}>
                {game.label}
              </Tabs.Tab>
            ))}
          </Tabs.List>
          {GAMES.map((game) => (
            <Tabs.Panel key={game.id} value={game.id} pt="sm">
              <InventoryGameSection
                game={game}
                active={activeGame === game.id}
              />
            </Tabs.Panel>
          ))}
        </Tabs>
      </Stack>
    </AppShellLayout>
  );
}
