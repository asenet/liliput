import { DataSourceInstanceSettings, ScopedVars } from '@grafana/data';
import { DataSourceWithBackend, getTemplateSrv } from '@grafana/runtime';
import { LiliputQuery, LiliputDataSourceOptions } from './types';

export class DataSource extends DataSourceWithBackend<LiliputQuery, LiliputDataSourceOptions> {
  constructor(instanceSettings: DataSourceInstanceSettings<LiliputDataSourceOptions>) {
    super(instanceSettings);
  }

  applyTemplateVariables(query: LiliputQuery, scopedVars: ScopedVars): LiliputQuery {
    const templateSrv = getTemplateSrv();
    return {
      ...query,
      expr: templateSrv.replace(query.expr || '', scopedVars),
      search: templateSrv.replace(query.search || '', scopedVars),
      severity: templateSrv.replace(query.severity || '', scopedVars, 'csv'),
    };
  }
}
