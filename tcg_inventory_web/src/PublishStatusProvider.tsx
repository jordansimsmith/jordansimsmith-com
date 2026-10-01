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
import type { PublishResponse } from './api/client';

interface PublishStatusContextValue {
  publish: PublishResponse | null;
  error: string | null;
  attentionDotVisible: boolean;
  refresh: () => Promise<void>;
  startPublish: () => Promise<void>;
}

const PublishStatusContext = createContext<PublishStatusContextValue | null>(
  null,
);

export function PublishStatusProvider({ children }: { children: ReactNode }) {
  const [publish, setPublish] = useState<PublishResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attentionDotVisible, setAttentionDotVisible] = useState(false);
  const requestRef = useRef<Promise<void> | null>(null);
  const refreshQueuedRef = useRef(false);
  const initialStatusReadRef = useRef(false);

  const refresh = useCallback(async (): Promise<void> => {
    if (requestRef.current) {
      refreshQueuedRef.current = true;
      await requestRef.current;
      if (refreshQueuedRef.current) {
        refreshQueuedRef.current = false;
        await refresh();
      }
      return;
    }

    const request = (async () => {
      try {
        const response = await apiClient.getPublish();
        setPublish(response);
        setError(null);
        if (!initialStatusReadRef.current) {
          initialStatusReadRef.current = true;
          setAttentionDotVisible(
            response.status !== null &&
              response.status !== 'queued' &&
              response.status !== 'running' &&
              response.pending_sku_count > 0,
          );
        }
      } catch (e) {
        const message = e instanceof Error ? e.message : '';
        if (message === 'Not Found') {
          setPublish({
            status: null,
            published_sku_count: 0,
            total_sku_count: 0,
            error: null,
            started_at: null,
            finished_at: null,
            pending_sku_count: 0,
          });
          setError(null);
          initialStatusReadRef.current = true;
          setAttentionDotVisible(false);
        } else {
          setError(message || 'Failed to load publish status');
        }
      } finally {
        requestRef.current = null;
      }
    })();

    requestRef.current = request;
    await request;
    if (refreshQueuedRef.current) {
      refreshQueuedRef.current = false;
      await refresh();
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const startPublish = useCallback(async () => {
    setAttentionDotVisible(false);
    await apiClient.createPublish();
    await refresh();
  }, [refresh]);

  const value = useMemo(
    () => ({ publish, error, attentionDotVisible, refresh, startPublish }),
    [attentionDotVisible, error, publish, refresh, startPublish],
  );

  return (
    <PublishStatusContext.Provider value={value}>
      {children}
    </PublishStatusContext.Provider>
  );
}

export function usePublishStatus(): PublishStatusContextValue | null {
  return useContext(PublishStatusContext);
}
