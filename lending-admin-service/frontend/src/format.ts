const inr = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2, minimumFractionDigits: 2 });
const inr0 = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });
const num = new Intl.NumberFormat('en-IN');

export const money = (v: number | null | undefined) => (v === null || v === undefined ? '-' : inr.format(v));
export const money0 = (v: number | null | undefined) => (v === null || v === undefined ? '-' : inr0.format(v));
export const count = (v: number | null | undefined) => (v === null || v === undefined ? '-' : num.format(v));

/** 12,34,567 -> "12.35 L", 1,23,45,678 -> "1.23 Cr" (Indian convention) for tiles. */
export function compactMoney(v: number | null | undefined): string {
  if (v === null || v === undefined) return '-';
  const abs = Math.abs(v);
  if (abs >= 1e7) return `₹${(v / 1e7).toFixed(2)} Cr`;
  if (abs >= 1e5) return `₹${(v / 1e5).toFixed(2)} L`;
  return inr0.format(v);
}

export function date(v: string | null | undefined): string {
  if (!v) return '-';
  const d = new Date(`${v.slice(0, 10)}T00:00:00`);
  if (isNaN(d.getTime())) return v;
  return d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
}

export function dateTime(v: string | null | undefined): string {
  if (!v) return '-';
  const d = new Date(v);
  if (isNaN(d.getTime())) return v;
  return d.toLocaleString('en-GB', { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

export const shortDate = (v: string) => {
  const d = new Date(`${v.slice(0, 10)}T00:00:00`);
  return d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short' });
};

export const pct = (v: number | null | undefined) => (v === null || v === undefined ? '-' : `${v.toFixed(2)}%`);

export function label(s: string | null | undefined): string {
  if (!s) return '-';
  return s.replace(/_/g, ' ').toLowerCase().replace(/^\w/, c => c.toUpperCase());
}

export type Tone = 'good' | 'warn' | 'bad' | 'info' | 'neutral';

export function accountTone(status: string): Tone {
  switch (status) {
    case 'ACTIVE': return 'good';
    case 'PENDING_DISBURSEMENT': return 'info';
    case 'NPA_SMA0': case 'NPA_SMA1': case 'NPA_SMA2': return 'warn';
    case 'NPA_SUBSTANDARD': case 'NPA_DOUBTFUL': case 'NPA_LOSS': case 'WRITTEN_OFF': return 'bad';
    default: return 'neutral'; // CLOSED, FORECLOSED
  }
}

export function todayIso(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export function downloadCsv(filename: string, header: string[], rows: (string | number | null | undefined)[][]) {
  const esc = (v: string | number | null | undefined) => {
    const s = v === null || v === undefined ? '' : String(v);
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const body = [header, ...rows].map(r => r.map(esc).join(',')).join('\r\n');
  const blob = new Blob(['﻿' + body], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = filename;
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
