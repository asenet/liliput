import React, { ChangeEvent } from 'react';
import { DataSourcePluginOptionsEditorProps } from '@grafana/data';
import { LiliputDataSourceOptions } from './types';

type Props = DataSourcePluginOptionsEditorProps<LiliputDataSourceOptions>;

export function ConfigEditor(props: Props) {
  const { onOptionsChange, options } = props;
  const jsonData = options.jsonData || {};

  const onLokiUrlChange = (event: ChangeEvent<HTMLInputElement>) => {
    onOptionsChange({
      ...options,
      jsonData: { ...jsonData, lokiUrl: event.target.value },
    });
  };

  return (
    <div style={{ maxWidth: 500 }}>
      <div style={{ marginBottom: 8 }}>
        <label htmlFor="lokiUrl" style={{ display: 'block', marginBottom: 4, fontWeight: 500 }}>
          Loki URL
        </label>
        <input
          id="lokiUrl"
          type="text"
          value={jsonData.lokiUrl || ''}
          onChange={onLokiUrlChange}
          placeholder="http://loki:3100"
          style={{ width: '100%', padding: '8px', border: '1px solid #ccc', borderRadius: 4 }}
        />
      </div>
    </div>
  );
}
