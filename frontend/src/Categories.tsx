import { useMemo, useState, type FormEvent } from 'react';
import { api, isApiError, type Category, type Subject } from './api';
import { duration, safeColor } from './format';
import { useDebounced, useLoad } from './hooks';
import { Pencil, Plus, Search, Trash } from './icons';
import { Badge, Card, ConfirmDialog, Loadable, Skeleton, Swatch, errorText } from './ui';

/** A restrained palette that reads in both themes. Category colour is data colour, the chrome stays neutral. */
export const PALETTE = [
  '#5b6cff', '#3f7a68', '#d9822b', '#c2416b', '#2f8fb3',
  '#8a5cc2', '#b59a2a', '#4d9e4f', '#a0522d', '#6b7280',
];

/** Built-ins that are not a destination for a mapping: "no mapping" is how a subject becomes Uncategorized. */
const NOT_ASSIGNABLE = new Set(['Uncategorized', 'Unattributed']);
const NEW = '__new__';

export function Categories({ onChanged }: { onChanged: () => void }) {
  const cats = useLoad(() => api.categories(), 'categories');
  const [query, setQuery] = useState('');
  const q = useDebounced(query.trim(), 250);
  const subjects = useLoad(() => api.subjects({ q, limit: 500 }), `subjects:${q}`);

  const changedCategories = () => {
    cats.reload();
    onChanged();
  };

  return (
    <>
      <div className="toolbar">
        <h1 className="vtitle">Categories</h1>
      </div>

      <Card title="Categories" hint="Shared by every device">
        <Loadable state={cats} skeleton={<Skeleton rows={4} lead={false} />}>
          {(list) => <CategoryList list={list} onChanged={changedCategories} />}
        </Loadable>
      </Card>

      <Card title="Apps and sites" hint="Last 30 days">
        <div className="searchbox">
          <Search size={16} />
          <input
            className="input"
            type="search"
            placeholder="Search apps and sites"
            aria-label="Search apps and sites"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
        <p className="help">
          A new mapping applies to every past day too. Computer time outside YouTube and LinkedIn is always Work or
          Personal computer, by your work window, so it is not listed here.
        </p>
        <Loadable state={subjects} skeleton={<Skeleton rows={6} lead={false} />}>
          {(list) => (
            <Loadable state={cats} skeleton={<Skeleton rows={6} lead={false} />}>
              {(categories) => (
                <SubjectList
                  key={q}
                  subjects={list}
                  categories={categories}
                  onCategoryCreated={() => cats.reload()}
                  onMapped={onChanged}
                />
              )}
            </Loadable>
          )}
        </Loadable>
      </Card>
    </>
  );
}

// ---------------------------------------------------------------- categories

function CategoryList({ list, onChanged }: { list: Category[]; onChanged: () => void }) {
  const [editing, setEditing] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [deleting, setDeleting] = useState<Category | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function remove() {
    if (!deleting) return;
    setBusy(true);
    setError(null);
    try {
      await api.deleteCategory(deleting.id);
      setDeleting(null);
      onChanged();
    } catch (e) {
      setError(
        isApiError(e, 'category_in_use')
          ? `"${deleting.name}" still has apps or sites mapped to it. Move them to another category first.`
          : errorText(e),
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <ul className="rows catrows">
        {list.map((c) =>
          editing === c.id ? (
            <li key={c.id} className="editing">
              <CategoryForm
                initial={c}
                existing={list}
                onDone={() => {
                  setEditing(null);
                  onChanged();
                }}
                onCancel={() => setEditing(null)}
              />
            </li>
          ) : (
            <li key={c.id}>
              <Swatch color={safeColor(c.color)} />
              <span className="grow label">{c.name}</span>
              {c.builtin && <Badge>Built-in</Badge>}
              <button className="iconbtn" onClick={() => setEditing(c.id)} aria-label={`Edit ${c.name}`}>
                <Pencil size={16} />
              </button>
              {!c.builtin && (
                <button
                  className="iconbtn"
                  onClick={() => {
                    setError(null);
                    setDeleting(c);
                  }}
                  aria-label={`Delete ${c.name}`}
                >
                  <Trash size={16} />
                </button>
              )}
            </li>
          ),
        )}
      </ul>
      {editing === null &&
        (adding ? (
          <div className="adder">
            <CategoryForm
              existing={list}
              onDone={() => {
                setAdding(false);
                onChanged();
              }}
              onCancel={() => setAdding(false)}
            />
          </div>
        ) : (
          <button className="btn ghost small adderbtn" onClick={() => setAdding(true)}>
            <Plus size={15} /> New category
          </button>
        ))}
      {deleting && (
        <ConfirmDialog
          title={`Delete "${deleting.name}"?`}
          confirmLabel="Delete"
          tone="danger"
          busy={busy}
          error={error}
          onConfirm={() => void remove()}
          onCancel={() => setDeleting(null)}
        >
          <p>Only a category with nothing mapped to it can be deleted.</p>
        </ConfirmDialog>
      )}
    </>
  );
}

/** Create (no `initial`) or edit a category. Built-ins keep their name; only the colour changes. */
export function CategoryForm({ initial, existing, onDone, onCancel, onCreated, compact }: {
  initial?: Category;
  existing: Category[];
  onDone: () => void;
  onCancel: () => void;
  onCreated?: (c: Category) => void;
  compact?: boolean;
}) {
  const [name, setName] = useState(initial?.name ?? '');
  const [color, setColor] = useState(safeColor(initial?.color, PALETTE[0]));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const builtin = initial?.builtin ?? false;
  const swatches = PALETTE.includes(color) ? PALETTE : [color, ...PALETTE];

  async function submit(e: FormEvent) {
    e.preventDefault();
    const clean = name.trim();
    if (!clean) return setError('Give the category a name.');
    if (clean.length > 60) return setError('Keep the name under 60 characters.');
    const clash = existing.find((c) => c.name.toLowerCase() === clean.toLowerCase() && c.id !== initial?.id);
    if (clash) return setError(`There is already a category called "${clash.name}".`);
    setBusy(true);
    setError(null);
    try {
      if (initial) {
        await api.updateCategory(initial.id, builtin ? initial.name : clean, color);
      } else {
        const created = await api.createCategory(clean, color);
        onCreated?.(created);
        setName('');
      }
      onDone();
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className={compact ? 'catform compact' : 'catform'} onSubmit={submit}>
      <div className="field">
        <label htmlFor={`cn-${initial?.id ?? 'new'}`}>Name</label>
        <input
          id={`cn-${initial?.id ?? 'new'}`}
          className="input"
          value={name}
          maxLength={60}
          disabled={builtin}
          autoFocus
          onChange={(e) => setName(e.target.value)}
        />
        {builtin && <span className="help">Built-in categories keep their name.</span>}
      </div>
      <fieldset className="palette">
        <legend>Colour</legend>
        {swatches.map((c, i) => (
          <label key={c} className="swatchpick">
            <input type="radio" name={`color-${initial?.id ?? 'new'}`} value={c} checked={color === c} onChange={() => setColor(c)} />
            <span style={{ background: c }} aria-hidden="true" />
            <span className="sr">Colour {i + 1}</span>
          </label>
        ))}
      </fieldset>
      {error && <p className="err" role="alert">{error}</p>}
      <div className="formrow">
        <button className="btn primary small" type="submit" disabled={busy}>
          {busy ? 'Saving…' : initial ? 'Save' : 'Create'}
        </button>
        <button className="btn ghost small" type="button" onClick={onCancel} disabled={busy}>Cancel</button>
      </div>
    </form>
  );
}

// ---------------------------------------------------------------- subjects

const KIND_LABEL = (s: Subject) => (s.key.endsWith('#shorts') ? 'Shorts' : s.kind === 'site' ? 'Site' : 'App');

function SubjectList({ subjects, categories, onCategoryCreated, onMapped }: {
  subjects: Subject[];
  categories: Category[];
  onCategoryCreated: () => void;
  onMapped: () => void;
}) {
  // Mappings made here, on top of what was loaded. Rows stay where they were
  // until the next load, so a row never jumps away from under the finger.
  const [mapped, setMapped] = useState<Record<string, string | null>>({});
  const groups = useMemo(() => {
    const byTime = (a: Subject, b: Subject) => b.minutes30d - a.minutes30d;
    return {
      unmapped: subjects.filter((s) => s.categoryId === null).sort(byTime),
      mapped: subjects.filter((s) => s.categoryId !== null).sort(byTime),
    };
  }, [subjects]);

  if (subjects.length === 0) return <p className="help">No apps or sites match.</p>;

  const row = (s: Subject) => (
    <SubjectRow
      key={s.key}
      subject={s}
      value={s.key in mapped ? mapped[s.key] : s.categoryId}
      categories={categories}
      onCategoryCreated={onCategoryCreated}
      onMapped={(categoryId) => {
        setMapped((m) => ({ ...m, [s.key]: categoryId }));
        onMapped();
      }}
    />
  );

  return (
    <div className="subjects">
      {groups.unmapped.length > 0 && (
        <>
          <h3 className="group">Uncategorized <span className="muted num">{groups.unmapped.length}</span></h3>
          <ul className="rows">{groups.unmapped.map(row)}</ul>
        </>
      )}
      {groups.mapped.length > 0 && (
        <>
          <h3 className="group">Categorized <span className="muted num">{groups.mapped.length}</span></h3>
          <ul className="rows">{groups.mapped.map(row)}</ul>
        </>
      )}
    </div>
  );
}

function SubjectRow({ subject, value, categories, onCategoryCreated, onMapped }: {
  subject: Subject;
  value: string | null;
  categories: Category[];
  onCategoryCreated: () => void;
  onMapped: (categoryId: string | null) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const options = categories.filter((c) => !(c.builtin && NOT_ASSIGNABLE.has(c.name)));
  const selectId = `map-${subject.key}`;

  async function map(categoryId: string | null) {
    setBusy(true);
    setError(null);
    try {
      await api.mapSubject(subject.key, categoryId);
      onMapped(categoryId);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <li className="subjectrow">
      <div className="sinfo">
        <span className="label">{subject.label}</span>
        <span className="muted small">
          <Badge>{KIND_LABEL(subject)}</Badge> <span className="num">{duration(subject.minutes30d)}</span>
        </span>
      </div>
      {creating ? (
        <CategoryForm
          compact
          existing={categories}
          onCreated={(c) => {
            onCategoryCreated();
            void map(c.id);
          }}
          onDone={() => setCreating(false)}
          onCancel={() => setCreating(false)}
        />
      ) : (
        <select
          id={selectId}
          className="input mapper"
          aria-label={`Category for ${subject.label}`}
          value={value ?? ''}
          disabled={busy}
          onChange={(e) => {
            if (e.target.value === NEW) setCreating(true);
            else void map(e.target.value || null);
          }}
        >
          <option value="">Uncategorized</option>
          {options.map((c) => (
            <option key={c.id} value={c.id}>{c.name}</option>
          ))}
          <option value={NEW}>+ New category…</option>
        </select>
      )}
      {error && <p className="err" role="alert">{error}</p>}
    </li>
  );
}
