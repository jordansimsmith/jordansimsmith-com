import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import type { ReactNode } from 'react';
import { apiClient } from './api/client';
import { getFinish, getGame } from './domain/games';
import type { Finish, Game, GameId, GamesResponse } from './api/client';

interface GamesContextValue {
  games: Game[];
  getGame: (gameId: GameId) => Game;
  getFinish: (gameId: GameId, finishId: Finish) => string;
}

export const GamesContext = createContext<GamesContextValue | null>(null);

interface GamesProviderProps {
  children: ReactNode;
  initialGames?: Game[];
}

export function GamesProvider({ children, initialGames }: GamesProviderProps) {
  const [games, setGames] = useState<Game[] | null>(initialGames ?? null);
  const requestRef = useRef<Promise<GamesResponse> | null>(null);
  const mountedRef = useRef(false);

  const loadGames = useCallback(async () => {
    try {
      requestRef.current ??= apiClient.getGames();
      const response = await requestRef.current;
      if (mountedRef.current && response.games.length > 0) {
        setGames(response.games);
      }
    } catch {
      return;
    }
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    if (initialGames === undefined) {
      void loadGames();
    }
    return () => {
      mountedRef.current = false;
    };
  }, [initialGames, loadGames]);

  const contextValue = useMemo<GamesContextValue | null>(() => {
    if (games === null || games.length === 0) {
      return null;
    }
    return {
      games,
      getGame: (gameId) => getGame(games, gameId),
      getFinish: (gameId, finishId) =>
        getFinish(getGame(games, gameId), finishId).display_name,
    };
  }, [games]);

  if (!contextValue) {
    return null;
  }

  return (
    <GamesContext.Provider value={contextValue}>
      {children}
    </GamesContext.Provider>
  );
}

export function useGames(): GamesContextValue {
  const context = useContext(GamesContext);
  if (!context) {
    throw new Error('useGames must be used within GamesProvider');
  }
  return context;
}
