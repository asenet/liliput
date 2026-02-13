import React, { ChangeEvent } from 'react';
import { QueryEditorProps } from '@grafana/data';
import { DataSource } from './datasource';
import { LiliputDataSourceOptions, LiliputQuery } from './types';

type Props = QueryEditorProps<DataSource, LiliputQuery, LiliputDataSourceOptions>;

export function QueryEditor({ query, onChange, onRunQuery }: Props) {
  const onExprChange = (event: ChangeEvent<HTMLInputElement>) => {
    onChange({ ...query, expr: event.target.value });
  };

  const onSearchChange = (event: ChangeEvent<HTMLInputElement>) => {
    onChange({ ...query, search: event.target.value });
  };

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Enter') {
      onRunQuery();
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <label htmlFor="expr" style={{ fontWeight: 500, minWidth: 70 }}>LogQL:</label>
        <input
          id="expr"
          type="text"
          value={query.expr || ''}
          onChange={onExprChange}
          onKeyDown={onKeyDown}
          placeholder='{compose_service="demo"}'
          style={{ flex: 1, padding: '8px', border: '1px solid #ccc', borderRadius: 4 }}
        />
      </div>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <label htmlFor="search" style={{ fontWeight: 500, minWidth: 70 }}>Search:</label>
        <input
          id="search"
          type="text"
          value={query.search || ''}
          onChange={onSearchChange}
          onKeyDown={onKeyDown}
          placeholder="Full-text search in rehydrated logs, e.g. alice logged in"
          style={{ flex: 1, padding: '8px', border: '1px solid #ccc', borderRadius: 4 }}
        />
      </div>
      <div style={{ fontSize: 11, color: '#888', paddingLeft: 78 }}>
        LogQL filters: by severity <code>{'{...} |~ "^[WE]"'}</code> &middot;
        by template ID <code>{'{...} |= "[1,"'}</code> &middot;
        by param value <code>{'{...} |= "\\"alice\\""'}</code>
      </div>
    </div>
  );
}
