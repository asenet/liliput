import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { QueryEditorProps } from '@grafana/data';
import { DataSource } from './datasource';
import { LiliputDataSourceOptions, LiliputQuery } from './types';

type Props = QueryEditorProps<DataSource, LiliputQuery, LiliputDataSourceOptions>;

const SEVERITIES = ['info', 'warning', 'error', 'debug', 'trace'] as const;
const OPERATORS = ['=', '!=', '=~', '!~'] as const;

// ── Tokens (8px grid) ──────────────────────────────────────────────────

const S = 8;          // base spacing unit
const H = 32;         // uniform control height
const R = 4;          // border-radius
const LABEL_W = 72;   // row label column width

const t = {
  bg0: '#111217',
  bg1: '#181b1f',
  bg2: '#1e2228',
  bg3: '#262a31',
  bgHover: '#2c3039',
  border: '#2e333a',
  borderHover: '#3d424a',
  borderFocus: '#4d6fbf',
  text: '#d5dbe1',
  textMuted: '#8b919a',
  textDim: '#5b6068',
  accent: '#6e9fff',
  accentFaded: 'rgba(110,159,255,0.08)',
  accentBorder: 'rgba(110,159,255,0.25)',
  accentBorderHover: 'rgba(110,159,255,0.45)',
  font: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
  mono: "Menlo, 'SF Mono', 'Cascadia Code', Consolas, monospace",
  shadow: '0 4px 12px rgba(0,0,0,0.35)',
};

const sevToken: Record<string, { fg: string; bg: string; border: string }> = {
  error:   { fg: '#f2495c', bg: 'rgba(242,73,92,0.10)',  border: 'rgba(242,73,92,0.25)' },
  warning: { fg: '#ff9830', bg: 'rgba(255,152,48,0.10)', border: 'rgba(255,152,48,0.25)' },
  info:    { fg: '#73bf69', bg: 'rgba(115,191,105,0.10)', border: 'rgba(115,191,105,0.25)' },
  debug:   { fg: '#5794f2', bg: 'rgba(87,148,242,0.10)', border: 'rgba(87,148,242,0.25)' },
  trace:   { fg: '#b877d9', bg: 'rgba(184,119,217,0.10)', border: 'rgba(184,119,217,0.25)' },
};

// ── Shared input style ─────────────────────────────────────────────────

const inputStyle = (mono?: boolean): React.CSSProperties => ({
  height: H, boxSizing: 'border-box' as const,
  padding: `0 ${S * 1.25}px`,
  fontSize: 13, lineHeight: `${H}px`,
  fontFamily: mono ? t.mono : t.font,
  color: t.text, background: t.bg1,
  border: `1px solid ${t.border}`, borderRadius: R,
  outline: 'none', transition: 'border-color 0.1s',
  width: '100%',
});

// ── Label filter helpers ───────────────────────────────────────────────

interface LabelFilter { label: string; op: string; value: string }

function parseExpr(expr: string): LabelFilter[] {
  const m = expr.match(/^\{(.*)\}$/);
  if (!m) { return []; }
  const inner = (m[1] || '').trim();
  if (!inner) { return []; }
  const out: LabelFilter[] = [];
  const re = /(\w+)\s*(=~|!~|!=|=)\s*"([^"]*)"/g;
  let match;
  while ((match = re.exec(inner)) !== null) {
    out.push({ label: match[1], op: match[2], value: match[3] });
  }
  return out;
}

function buildExpr(filters: LabelFilter[]): string {
  if (!filters.length) { return ''; }
  return '{' + filters.map(f => `${f.label}${f.op}"${f.value}"`).join(', ') + '}';
}

// ── Dropdown ───────────────────────────────────────────────────────────

const Chevron = React.memo(({ open }: { open: boolean }) => (
  <svg width="8" height="5" viewBox="0 0 8 5" style={{ flexShrink: 0, opacity: 0.4, transition: 'transform 0.12s', transform: open ? 'rotate(180deg)' : 'none' }}>
    <path d="M.7.7 4 4l3.3-3.3" stroke="currentColor" strokeWidth="1.4" fill="none" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
));
Chevron.displayName = 'Chevron';

function Dropdown({ options, value, placeholder, onChange, width, mono, searchable }: {
  options: string[]; value: string; placeholder: string; onChange: (v: string) => void;
  width?: number; mono?: boolean; searchable?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const ref = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!open) { return; }
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) { setOpen(false); setQ(''); }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  useEffect(() => { if (open && searchable) { inputRef.current?.focus(); } }, [open, searchable]);

  const filtered = useMemo(() => {
    if (!q) { return options; }
    const lc = q.toLowerCase();
    return options.filter(o => o.toLowerCase().includes(lc));
  }, [options, q]);

  return (
    <div ref={ref} style={{ position: 'relative', width: width ?? 'auto', minWidth: width ?? 100 }}>
      {/* Trigger */}
      <div
        onClick={() => setOpen(!open)}
        style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 6,
          height: H, padding: `0 ${S}px 0 ${S * 1.25}px`, cursor: 'pointer',
          background: open ? t.bg3 : t.bg2, borderRadius: R,
          border: `1px solid ${open ? t.borderFocus : t.border}`,
          color: value ? t.text : t.textDim,
          fontSize: 13, fontFamily: mono ? t.mono : t.font,
          whiteSpace: 'nowrap', overflow: 'hidden', boxSizing: 'border-box',
          transition: 'border-color 0.1s',
        }}
      >
        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', flex: 1 }}>{value || placeholder}</span>
        <Chevron open={open} />
      </div>

      {/* Menu */}
      {open && (
        <div style={{
          position: 'absolute', top: H + 2, left: 0, zIndex: 1000,
          minWidth: Math.max(width ?? 100, 160), width: '100%',
          background: t.bg1, border: `1px solid ${t.borderHover}`,
          borderRadius: R, boxShadow: t.shadow,
          maxHeight: 220, overflowY: 'auto',
        }}>
          {searchable && options.length > 5 && (
            <div style={{ padding: S / 2 }}>
              <input ref={inputRef} type="text" value={q}
                onChange={e => setQ(e.target.value)} placeholder="Filter..."
                style={{ ...inputStyle(), height: 28, fontSize: 12, background: t.bg0, padding: `0 ${S}px` }}
              />
            </div>
          )}
          {!filtered.length && <div style={{ padding: `${S}px ${S * 1.25}px`, fontSize: 12, color: t.textDim }}>No results</div>}
          {filtered.map(opt => (
            <div key={opt}
              onClick={() => { onChange(opt); setOpen(false); setQ(''); }}
              onMouseEnter={e => { (e.currentTarget).style.background = t.bgHover; }}
              onMouseLeave={e => { (e.currentTarget).style.background = opt === value ? t.accentFaded : 'transparent'; }}
              style={{
                padding: `6px ${S * 1.25}px`, fontSize: 13, cursor: 'pointer',
                color: opt === value ? t.accent : t.text,
                background: opt === value ? t.accentFaded : 'transparent',
                fontFamily: mono ? t.mono : t.font,
              }}
            >
              {opt}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ── Label filter row (inline dropdowns) ────────────────────────────────

function LabelFilterRow({ filter, labels, datasource, onUpdate, onRemove }: {
  filter: LabelFilter; labels: string[]; datasource: DataSource;
  onUpdate: (f: LabelFilter) => void; onRemove: () => void;
}) {
  const [values, setValues] = useState<string[]>([]);
  useEffect(() => { if (filter.label) { datasource.getLabelValues(filter.label).then(setValues); } }, [datasource, filter.label]);

  return (
    <div style={{ display: 'inline-flex', alignItems: 'center', gap: 2 }}>
      <Dropdown options={labels} value={filter.label} placeholder="label"
        onChange={v => onUpdate({ ...filter, label: v, value: '' })} width={130} searchable />
      <Dropdown options={[...OPERATORS]} value={filter.op} placeholder="="
        onChange={v => onUpdate({ ...filter, op: v })} width={52} mono />
      <Dropdown options={values} value={filter.value} placeholder="value"
        onChange={v => onUpdate({ ...filter, value: v })} width={160} searchable />
      <button onClick={onRemove} title="Remove"
        onMouseEnter={e => { e.currentTarget.style.color = '#f2495c'; e.currentTarget.style.background = 'rgba(242,73,92,0.1)'; }}
        onMouseLeave={e => { e.currentTarget.style.color = t.textDim; e.currentTarget.style.background = 'transparent'; }}
        style={{
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          width: 24, height: 24, border: 'none', background: 'transparent',
          color: t.textDim, cursor: 'pointer', borderRadius: R, fontSize: 15, padding: 0,
        }}
      >&times;</button>
    </div>
  );
}

// ── Pill ───────────────────────────────────────────────────────────────

function Pill({ text, onRemove }: { text: string; onRemove: () => void }) {
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 4,
      height: H, padding: `0 4px 0 ${S * 1.25}px`, borderRadius: R,
      fontSize: 12, fontFamily: t.mono, fontWeight: 500,
      background: t.accentFaded, color: t.accent, border: `1px solid ${t.accentBorder}`,
      boxSizing: 'border-box',
    }}>
      {text}
      <button onClick={onRemove}
        onMouseEnter={e => { e.currentTarget.style.opacity = '1'; }}
        onMouseLeave={e => { e.currentTarget.style.opacity = '0.5'; }}
        style={{
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          width: 22, height: 22, border: 'none', background: 'transparent',
          color: t.accent, cursor: 'pointer', borderRadius: R, fontSize: 14, padding: 0, opacity: 0.5,
        }}
      >&times;</button>
    </span>
  );
}

// ── Main ───────────────────────────────────────────────────────────────

export function QueryEditor({ query, onChange, onRunQuery, datasource }: Props) {
  const [mode, setMode] = useState<'builder' | 'code'>('builder');
  const [labels, setLabels] = useState<string[]>([]);
  const [editingFilters, setEditingFilters] = useState<LabelFilter[]>([]);
  const [showNewFilter, setShowNewFilter] = useState(false);

  useEffect(() => { datasource.getLabels().then(setLabels); }, [datasource]);

  const filters = parseExpr(query.expr || '');

  const updateFilter = useCallback((i: number, u: LabelFilter) => {
    const next = [...filters]; next[i] = u;
    onChange({ ...query, expr: buildExpr(next) });
  }, [filters, query, onChange]);

  const removeFilter = useCallback((i: number) => {
    onChange({ ...query, expr: buildExpr(filters.filter((_, j) => j !== i)) });
    setTimeout(onRunQuery, 0);
  }, [filters, query, onChange, onRunQuery]);

  const addFilter = useCallback(() => {
    setEditingFilters([...filters, { label: '', op: '=', value: '' }]);
    setShowNewFilter(true);
  }, [filters]);

  const updateNewFilter = useCallback((i: number, u: LabelFilter) => {
    const next = [...editingFilters]; next[i] = u; setEditingFilters(next);
    if (u.label && u.value) {
      onChange({ ...query, expr: buildExpr(next) });
      setShowNewFilter(false);
      setTimeout(onRunQuery, 0);
    }
  }, [editingFilters, query, onChange, onRunQuery]);

  const cancelNew = useCallback(() => { setShowNewFilter(false); setEditingFilters([]); }, []);
  const display = showNewFilter ? editingFilters : filters;

  // Severity
  const selectedSev = useMemo(() => new Set(
    (query.severity || '').split(',').map(s => s.trim().toLowerCase()).filter(s => s && s !== 'all')
  ), [query.severity]);

  const toggleSev = useCallback((sev: string) => {
    const next = new Set(selectedSev);
    next.has(sev) ? next.delete(sev) : next.add(sev);
    onChange({ ...query, severity: !next.size || next.size === SEVERITIES.length ? '' : Array.from(next).join(',') });
    setTimeout(onRunQuery, 0);
  }, [selectedSev, query, onChange, onRunQuery]);

  const allSev = selectedSev.size === 0;

  const onExprChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => onChange({ ...query, expr: e.target.value }), [query, onChange]);
  const onSearchChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => onChange({ ...query, search: e.target.value }), [query, onChange]);
  const onKeyDown = useCallback((e: React.KeyboardEvent) => { if (e.key === 'Enter') { onRunQuery(); } }, [onRunQuery]);

  const focusBorder = useCallback((e: React.FocusEvent<HTMLInputElement>) => { e.target.style.borderColor = t.borderFocus; }, []);
  const blurBorder = useCallback((e: React.FocusEvent<HTMLInputElement>) => { e.target.style.borderColor = t.border; }, []);

  return (
    <div style={{ fontFamily: t.font }}>
      {/* ── Tabs ── */}
      <div style={{ display: 'flex', gap: 0, position: 'relative', zIndex: 1 }}>
        {(['builder', 'code'] as const).map(m => {
          const on = mode === m;
          return (
            <button key={m} onClick={() => setMode(m)}
              onMouseEnter={e => { if (!on) { e.currentTarget.style.color = t.text; } }}
              onMouseLeave={e => { if (!on) { e.currentTarget.style.color = t.textMuted; } }}
              style={{
                height: 36, padding: `0 ${S * 2.5}px`, fontSize: 13, fontWeight: 500,
                cursor: 'pointer', userSelect: 'none', border: 'none', fontFamily: t.font,
                borderRadius: `${R}px ${R}px 0 0`,
                background: on ? t.bg3 : 'transparent',
                color: on ? t.accent : t.textMuted,
                borderBottom: `2px solid ${on ? t.accent : 'transparent'}`,
                transition: 'color 0.1s',
                marginBottom: -1,
              }}
            >
              {m === 'builder' ? 'Builder' : 'Code'}
            </button>
          );
        })}
      </div>

      {/* ── Panel ── */}
      <div style={{
        background: t.bg3, border: `1px solid ${t.border}`,
        borderRadius: `0 ${R}px ${R}px ${R}px`,
        padding: S * 2,
        display: 'flex', flexDirection: 'column', gap: S * 1.5,
      }}>
        {mode === 'code' ? (
          <>
            <Row label="LogQL">
              <input type="text" value={query.expr || ''} onChange={onExprChange} onKeyDown={onKeyDown}
                onFocus={focusBorder} onBlur={blurBorder} spellCheck={false}
                placeholder='{compose_service="demo"} |= "alice"'
                style={{ ...inputStyle(true), flex: 1 }} />
            </Row>
            <Row label="Search">
              <input type="text" value={query.search || ''} onChange={onSearchChange} onKeyDown={onKeyDown}
                onFocus={focusBorder} onBlur={blurBorder}
                placeholder="Full-text search in rehydrated logs"
                style={{ ...inputStyle(), flex: 1 }} />
            </Row>
          </>
        ) : (
          <>
            {/* Labels */}
            <Row label="Labels">
              <div style={{ display: 'flex', gap: S, flexWrap: 'wrap', alignItems: 'center' }}>
                {display.map((f, i) => {
                  const isNew = showNewFilter && i >= filters.length;
                  if (isNew) {
                    return <LabelFilterRow key={i} filter={f} labels={labels} datasource={datasource}
                      onUpdate={u => updateNewFilter(i, u)} onRemove={cancelNew} />;
                  }
                  return f.label && f.value
                    ? <Pill key={i} text={`${f.label} ${f.op} "${f.value}"`} onRemove={() => removeFilter(i)} />
                    : <LabelFilterRow key={i} filter={f} labels={labels} datasource={datasource}
                        onUpdate={u => updateFilter(i, u)} onRemove={() => removeFilter(i)} />;
                })}
                {!showNewFilter && (
                  <button onClick={addFilter}
                    onMouseEnter={e => { const el = e.currentTarget; el.style.borderColor = t.accentBorderHover; el.style.color = t.accent; el.style.background = t.accentFaded; }}
                    onMouseLeave={e => { const el = e.currentTarget; el.style.borderColor = t.borderHover; el.style.color = t.textMuted; el.style.background = 'transparent'; }}
                    style={{
                      display: 'inline-flex', alignItems: 'center', gap: 5,
                      height: H, padding: `0 ${S * 1.5}px`, borderRadius: R,
                      fontSize: 12, fontWeight: 500, fontFamily: t.font,
                      cursor: 'pointer', border: `1px dashed ${t.borderHover}`,
                      color: t.textMuted, background: 'transparent',
                      transition: 'all 0.1s', boxSizing: 'border-box',
                    }}
                  >
                    <svg width="10" height="10" viewBox="0 0 10 10" fill="none">
                      <path d="M5 1v8M1 5h8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
                    </svg>
                    Label
                  </button>
                )}
              </div>
            </Row>

            {/* Search */}
            <Row label="Search">
              <div style={{ flex: 1, position: 'relative' }}>
                <svg width="14" height="14" viewBox="0 0 16 16" fill="none"
                  style={{ position: 'absolute', left: S * 1.25, top: '50%', transform: 'translateY(-50%)', opacity: 0.3, pointerEvents: 'none', color: t.textMuted }}>
                  <circle cx="7" cy="7" r="5.5" stroke="currentColor" strokeWidth="1.5" />
                  <path d="M11 11l3.5 3.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
                </svg>
                <input type="text" value={query.search || ''} onChange={onSearchChange} onKeyDown={onKeyDown}
                  onFocus={focusBorder} onBlur={blurBorder}
                  placeholder="Search in rehydrated logs..."
                  style={{ ...inputStyle(), paddingLeft: S * 3.75, flex: 1 }} />
              </div>
            </Row>

            {/* Severity */}
            <Row label="Severity">
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
                {SEVERITIES.map(sev => {
                  const on = allSev || selectedSev.has(sev);
                  const sc = sevToken[sev];
                  return (
                    <button key={sev} onClick={() => toggleSev(sev)}
                      style={{
                        display: 'inline-flex', alignItems: 'center', gap: 6,
                        height: 28, padding: `0 ${S * 1.5}px 0 ${S}px`, borderRadius: 14,
                        fontSize: 12, fontWeight: 500, fontFamily: t.font,
                        cursor: 'pointer', boxSizing: 'border-box',
                        background: on ? sc.bg : 'transparent',
                        border: `1px solid ${on ? sc.border : t.border}`,
                        color: on ? sc.fg : t.textDim,
                        transition: 'all 0.1s',
                      }}
                    >
                      <span style={{
                        width: 6, height: 6, borderRadius: '50%',
                        background: on ? sc.fg : t.textDim, opacity: on ? 1 : 0.3,
                        transition: 'all 0.1s',
                      }} />
                      {sev}
                    </button>
                  );
                })}
              </div>
            </Row>
          </>
        )}
      </div>
    </div>
  );
}

// ── Row layout ─────────────────────────────────────────────────────────

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: S * 1.5, minHeight: H }}>
      <span style={{
        width: LABEL_W, flexShrink: 0,
        fontSize: 11, fontWeight: 600, letterSpacing: '0.5px',
        textTransform: 'uppercase', color: t.textMuted,
      }}>
        {label}
      </span>
      <div style={{ flex: 1, display: 'flex', alignItems: 'center', minWidth: 0 }}>
        {children}
      </div>
    </div>
  );
}
