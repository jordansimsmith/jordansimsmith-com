import { createContext, useContext, useMemo, useState } from 'react';

interface LibraryStatsContextValue {
  rollingCount: number | null;
  setRollingCount: (count: number | null) => void;
}

const LibraryStatsContext = createContext<LibraryStatsContextValue | null>(
  null,
);

export function LibraryStatsProvider({
  children,
}: {
  children: React.ReactNode;
}) {
  const [rollingCount, setRollingCount] = useState<number | null>(null);
  const value = useMemo(
    () => ({ rollingCount, setRollingCount }),
    [rollingCount],
  );
  return (
    <LibraryStatsContext.Provider value={value}>
      {children}
    </LibraryStatsContext.Provider>
  );
}

export function useLibraryStats(): LibraryStatsContextValue {
  const value = useContext(LibraryStatsContext);
  if (!value) {
    return {
      rollingCount: null,
      setRollingCount: () => {},
    };
  }
  return value;
}
