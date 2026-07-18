import { useCallback, useEffect, useRef, useState } from 'react';
import { GateError } from './serverApi';

export interface PollState<T> {
  data: T | null;
  loading: boolean;
  /** the HTTP status (401/403) when the caller is gated out, else null */
  gate: number | null;
  error: string | null;
  reload: () => Promise<void>;
}

/**
 * Poll a server resource on an interval, mapping a {@link GateError} to `gate` (so widgets render a
 * sign-in prompt rather than an error) and everything else to `error`. The loader may change every
 * render (it usually closes over nothing); it is read through a ref so the interval stays stable.
 */
export function useServerPoll<T>(load: () => Promise<T>, intervalMs = 3000): PollState<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [gate, setGate] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const loadRef = useRef(load);
  loadRef.current = load;

  const reload = useCallback(async () => {
    try {
      const d = await loadRef.current();
      setData(d);
      setGate(null);
      setError(null);
    } catch (e) {
      if (e instanceof GateError) setGate(e.status);
      else setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    reload();
    const h = window.setInterval(reload, intervalMs);
    return () => window.clearInterval(h);
  }, [reload, intervalMs]);

  return { data, loading, gate, error, reload };
}
