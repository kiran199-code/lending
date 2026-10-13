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

export interface Polled<T> { data: T | null; error: string | null; lastChecked: Date | null; refresh: () => void }

/**
 * Calls `fn` now and then every `intervalMs` (a value that can change as the data changes, e.g. faster
 * while something is running). Never overlaps two calls, skips ticks while the tab is hidden, and keeps
 * the last good data when a call fails so the screen does not blank out.
 */
export function usePolling<T>(fn: () => Promise<T>, intervalMs: (data: T | null) => number): Polled<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [lastChecked, setLastChecked] = useState<Date | null>(null);
  const inflight = useRef(false);
  const dataRef = useRef<T | null>(null);
  const fnRef = useRef(fn);
  const intervalRef = useRef(intervalMs);
  fnRef.current = fn;
  intervalRef.current = intervalMs;

  const poll = useCallback(async () => {
    if (inflight.current) return;
    inflight.current = true;
    try {
      const d = await fnRef.current();
      dataRef.current = d;
      setData(d);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      inflight.current = false;
      setLastChecked(new Date());
    }
  }, []);

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout>;
    let stopped = false;
    const loop = async () => {
      if (!document.hidden) await poll();
      if (!stopped) timer = setTimeout(loop, intervalRef.current(dataRef.current));
    };
    loop();
    const onVisible = () => { if (!document.hidden) poll(); };
    document.addEventListener('visibilitychange', onVisible);
    return () => { stopped = true; clearTimeout(timer); document.removeEventListener('visibilitychange', onVisible); };
  }, [poll]);

  return { data, error, lastChecked, refresh: poll };
}
