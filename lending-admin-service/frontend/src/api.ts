// Thin fetch wrapper. Credentials live in sessionStorage so a browser restart logs the user out.
const KEY = 'lending-admin-auth';

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

// Kept in memory too, so login still works when sessionStorage is blocked.
let memoryToken: string | null = null;

export function getAuth(): string | null {
  try { return sessionStorage.getItem(KEY); } catch { return null; }
}
export function setAuth(username: string, password: string) {
  const token = btoa(unescape(encodeURIComponent(`${username}:${password}`)));
  memoryToken = token;
  try { sessionStorage.setItem(KEY, token); } catch { /* storage blocked */ }
}
export function clearAuth() {
  memoryToken = null;
  try { sessionStorage.removeItem(KEY); } catch { /* ignore */ }
}
function token() { return getAuth() ?? memoryToken; }

let onUnauthorized: () => void = () => {};
export function setUnauthorizedHandler(fn: () => void) { onUnauthorized = fn; }

export type Params = Record<string, string | number | boolean | null | undefined>;

function qs(params?: Params): string {
  if (!params) return '';
  const u = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') u.set(k, String(v));
  });
  const s = u.toString();
  return s ? `?${s}` : '';
}

async function request(path: string, params?: Params): Promise<Response> {
  const t = token();
  const res = await fetch(`/api/admin${path}${qs(params)}`, { headers: t ? { Authorization: `Basic ${t}` } : {} });
  if (res.status === 401) {
    onUnauthorized();
    throw new ApiError(401, 'Please sign in');
  }
  if (!res.ok) {
    let msg = `Request failed (${res.status})`;
    try { const j = await res.json(); if (j.message) msg = j.message; } catch { /* not json */ }
    throw new ApiError(res.status, msg);
  }
  return res;
}

export async function get<T>(path: string, params?: Params): Promise<T> {
  return (await request(path, params)).json() as Promise<T>;
}

export async function download(path: string, fallbackName: string) {
  const res = await request(path);
  const blob = await res.blob();
  const cd = res.headers.get('Content-Disposition') ?? '';
  const m = /filename="?([^";]+)"?/i.exec(cd);
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = m ? m[1] : fallbackName;
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
