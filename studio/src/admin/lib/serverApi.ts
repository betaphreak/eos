// serverApi.ts — thin client for the CivStudio (Java spectator) server's admin API, used by the
// admin homepage ops widgets. The server lives on a *different origin* (dev.civstudio.com) than the
// Strapi admin (civstudio.com), but the two are the SAME registrable domain, so:
//   - CORS on the server already allows the civstudio.com origins WITH credentials (WebConfig), and
//   - the login session cookie is SameSite=Lax scoped to civstudio.com, so it rides these
//     cross-subdomain requests (same-site, not third-party).
// So the widgets call the server directly with `credentials:'include'` and the server enforces its
// existing ROLE_ADMIN. A 401/403 surfaces as a sign-in gate (the operator needs a *server* login,
// separate from their Strapi admin login).

// Configurable at build time (env VITE_CIVSTUDIO_SERVER); defaults to the live dev server.
const RAW = (import.meta as any)?.env?.VITE_CIVSTUDIO_SERVER as string | undefined;
export const serverBase = (RAW || 'https://dev.civstudio.com').replace(/\/+$/, '');

export const signInUrl = `${serverBase}/api/auth/steam/login`;

/** Thrown on 401/403 so widgets can render the sign-in gate instead of an error. */
export class GateError extends Error {
  constructor(public readonly status: number) {
    super(status === 401 ? 'not signed in' : 'not an admin');
  }
}

export async function serverFetch<T = any>(
  method: 'GET' | 'POST',
  path: string,
  body?: unknown,
): Promise<T> {
  const res = await fetch(`${serverBase}${path}`, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    credentials: 'include',
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (res.status === 401 || res.status === 403) throw new GateError(res.status);
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status}`);
  if (res.status === 204) return {} as T;
  return (await res.json().catch(() => ({}))) as T;
}

// ---- response shapes (GET /api/admin/status, GET /api/sessions) ----
export interface ServerStatus {
  plots: { cached: number; total: number; mapVersion: number; generating: number | null; storageUrl?: string };
  server: { uptimeMs: number; heapUsedMb: number; heapMaxMb: number; sessions: number; admins: number; you?: string };
}

export interface SessionRow {
  id: string;
  owner?: string;
  state: 'CREATED' | 'RUNNING' | 'PAUSED' | 'STOPPED' | string;
  tick?: number;
}

export function formatDuration(ms: number): string {
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return h ? `${h}h ${m}m` : `${m}m`;
}
