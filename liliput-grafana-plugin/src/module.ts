import { DataSourcePlugin } from '@grafana/data';
import { DataSource } from './datasource';
import { ConfigEditor } from './ConfigEditor';
import { QueryEditor } from './QueryEditor';
import { LiliputQuery, LiliputDataSourceOptions } from './types';

export const plugin = new DataSourcePlugin<DataSource, LiliputQuery, LiliputDataSourceOptions>(DataSource)
  .setConfigEditor(ConfigEditor)
  .setQueryEditor(QueryEditor);
