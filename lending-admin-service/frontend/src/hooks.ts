import { useCallback, useEffect, useRef, useState } from 'react';

export interface Loaded<T> { data: T | null; error: string | null; loading: boolean; reload: () => void }

/** Runs `fn` on mount and whenever `deps` change; ignores results that arrive after a newer request. */
export function useLoad<T>(fn: () => Promise<T>, deps: unknown[]): Loaded<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const seq = useRef(0);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    const id = ++seq.current;
    setLoading(true);
    setError(null);
    fn().then(
      d => { if (id === seq.current) { setData(d); setLoading(false); } },
      e => { if (id === seq.current) { setError(e instanceof Error ? e.message : String(e)); setLoading(false); } },
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  const reload = useCallback(() => setTick(t => t + 1), []);
  return { data, error, loading, reload };
}
