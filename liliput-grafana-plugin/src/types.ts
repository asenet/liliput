import { DataQuery, DataSourceJsonData } from '@grafana/data';

export interface LiliputQuery extends DataQuery {
  expr?: string;
  search?: string;
  severity?: string;
}

export interface LiliputDataSourceOptions extends DataSourceJsonData {
  lokiUrl?: string;
}
