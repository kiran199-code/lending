import { ReactNode } from 'react';

export interface Col<T> {
  key: string;
  header: string;
  render: (row: T) => ReactNode;
  align?: 'right' | 'center';
  sortKey?: string;
}

interface Props<T> {
  columns: Col<T>[];
  rows: T[];
  rowKey: (row: T) => string | number;
  onRowClick?: (row: T) => void;
  rowClass?: (row: T) => string | undefined;
  sort?: string;
  dir?: string;
  onSort?: (sortKey: string) => void;
  empty?: ReactNode;
  footer?: ReactNode;
}

export default function DataTable<T>({ columns, rows, rowKey, onRowClick, rowClass, sort, dir, onSort, empty, footer }: Props<T>) {
  return (
    <div className="table-wrap">
      <table className="table">
        <thead>
          <tr>
            {columns.map(c => {
              const sortable = !!c.sortKey && !!onSort;
              const active = sortable && sort === c.sortKey;
              return (
                <th
                  key={c.key}
                  className={`${c.align ?? ''} ${sortable ? 'sortable' : ''}`}
                  aria-sort={active ? (dir === 'asc' ? 'ascending' : 'descending') : undefined}
                  onClick={sortable ? () => onSort!(c.sortKey!) : undefined}
                >
                  {c.header}
                  {active && <span aria-hidden> {dir === 'asc' ? '▲' : '▼'}</span>}
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 && (
            <tr><td colSpan={columns.length} className="empty-cell">{empty ?? 'Nothing to show'}</td></tr>
          )}
          {rows.map(r => (
            <tr key={rowKey(r)} className={`${onRowClick ? 'clickable' : ''} ${rowClass?.(r) ?? ''}`} onClick={onRowClick ? () => onRowClick(r) : undefined}>
              {columns.map(c => <td key={c.key} className={c.align ?? ''}>{c.render(r)}</td>)}
            </tr>
          ))}
        </tbody>
        {footer && <tfoot>{footer}</tfoot>}
      </table>
    </div>
  );
}
