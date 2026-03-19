import { DataSourceInstanceSettings, ScopedVars } from '@grafana/data';
import { DataSourceWithBackend, getBackendSrv, getTemplateSrv } from '@grafana/runtime';
import { LiliputQuery, LiliputDataSourceOptions } from './types';

interface LokiLabelsResponse {
  data?: string[];
}

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

  private resourceUrl(path: string): string {
    return `/api/datasources/uid/${this.uid}/resources/${path}`;
  }

  async getLabels(): Promise<string[]> {
    try {
      const resp: LokiLabelsResponse = await getBackendSrv().get(this.resourceUrl('labels'));
      return resp.data || [];
    } catch {
      return [];
    }
  }

  async getLabelValues(label: string): Promise<string[]> {
    try {
      const resp: LokiLabelsResponse = await getBackendSrv().get(
        this.resourceUrl(`label/${encodeURIComponent(label)}/values`)
      );
      return resp.data || [];
    } catch {
      return [];
    }
  }
}
