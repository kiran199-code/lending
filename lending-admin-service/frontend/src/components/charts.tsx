import { Area, AreaChart, Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { compactMoney, count, label, shortDate } from '../format';
import type { DailyAmount, StatusCount } from '../types';

// One brand hue per chart (single series, so no categorical palette); grid and axes stay recessive.
const MARK = 'var(--chart-mark)';
const GRID = 'var(--chart-grid)';
const AXIS = { fontSize: 12, fill: 'var(--muted)' } as const;

function Tip({ active, payload, labelText, money }: { active?: boolean; payload?: { value: number }[]; labelText?: string; money: boolean }) {
  if (!active || !payload?.length) return null;
  return (
    <div className="tip">
      <div className="muted">{labelText}</div>
      <strong>{money ? compactMoney(payload[0].value) : count(payload[0].value)}</strong>
    </div>
  );
}

export function StatusBars({ data }: { data: StatusCount[] }) {
  const rows = data.map(d => ({ name: label(d.status), value: d.accounts }));
  return (
    <div role="img" aria-label="Accounts by status" style={{ height: Math.max(180, rows.length * 34 + 20) }}>
      <ResponsiveContainer>
        <BarChart data={rows} layout="vertical" margin={{ left: 8, right: 24, top: 4, bottom: 4 }}>
          <CartesianGrid horizontal={false} stroke={GRID} />
          <XAxis type="number" allowDecimals={false} tick={AXIS} axisLine={false} tickLine={false} />
          <YAxis type="category" dataKey="name" width={150} tick={AXIS} axisLine={false} tickLine={false} />
          <Tooltip cursor={{ fill: 'var(--hover)' }} content={({ active, payload, label: l }) => <Tip active={active} payload={payload as never} labelText={String(l)} money={false} />} />
          <Bar dataKey="value" fill={MARK} radius={[0, 4, 4, 0]} barSize={16} isAnimationActive={false} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

export function DailyArea({ data, name }: { data: DailyAmount[]; name: string }) {
  const rows = data.map(d => ({ day: shortDate(d.date), value: d.amount }));
  return (
    <div role="img" aria-label={name} style={{ height: 220 }}>
      <ResponsiveContainer>
        <AreaChart data={rows} margin={{ left: 0, right: 12, top: 8, bottom: 0 }}>
          <CartesianGrid vertical={false} stroke={GRID} />
          <XAxis dataKey="day" tick={AXIS} axisLine={false} tickLine={false} minTickGap={24} />
          <YAxis tick={AXIS} axisLine={false} tickLine={false} width={72} tickFormatter={v => compactMoney(Number(v))} />
          <Tooltip content={({ active, payload, label: l }) => <Tip active={active} payload={payload as never} labelText={String(l)} money />} />
          <Area type="monotone" dataKey="value" stroke={MARK} strokeWidth={2} fill={MARK} fillOpacity={0.12} dot={false} activeDot={{ r: 4 }} isAnimationActive={false} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}

export function DailyBars({ data, name }: { data: DailyAmount[]; name: string }) {
  const rows = data.map(d => ({ day: shortDate(d.date), value: d.amount }));
  return (
    <div role="img" aria-label={name} style={{ height: 220 }}>
      <ResponsiveContainer>
        <BarChart data={rows} margin={{ left: 0, right: 12, top: 8, bottom: 0 }}>
          <CartesianGrid vertical={false} stroke={GRID} />
          <XAxis dataKey="day" tick={AXIS} axisLine={false} tickLine={false} minTickGap={24} />
          <YAxis tick={AXIS} axisLine={false} tickLine={false} width={72} tickFormatter={v => compactMoney(Number(v))} />
          <Tooltip cursor={{ fill: 'var(--hover)' }} content={({ active, payload, label: l }) => <Tip active={active} payload={payload as never} labelText={String(l)} money />} />
          <Bar dataKey="value" fill={MARK} radius={[4, 4, 0, 0]} maxBarSize={18} isAnimationActive={false} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
