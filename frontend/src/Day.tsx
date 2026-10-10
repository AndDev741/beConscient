import { api, type DayView } from './api';
import { BarList, HourlyChart } from './charts';
import { count, dateLong, duration, durationParts, safeColor, shiftDays, todayIso } from './format';
import { useInterval, useLoad, useRevision } from './hooks';
import { ChevronLeft, ChevronRight, Desktop, Phone } from './icons';
import { Badge, Card, Empty, Loadable, Notice, Progress, Skeleton } from './ui';

export function Day({ date, onDate, revision, onTrace }: {
  date: string;
  onDate: (d: string) => void;
  revision: number;
  onTrace?: (segmentId: string) => void;
}) {
  const today = todayIso();
  const day = useLoad(() => api.day(date), date);
  useRevision(day.reload, revision);

  // Today and days still arriving change under the user's eyes; a rebuild moves fast
  const d = day.data;
  const live = d !== null && (d.derivation.rebuilding || d.state !== 'closed');
  useInterval(day.reload, d?.derivation.rebuilding ? 15_000 : 300_000, live);

  const relative = date === today ? 'Today' : date === shiftDays(today, -1) ? 'Yesterday' : null;

  return (
    <>
      <div className="daynav">
        <button className="iconbtn" onClick={() => onDate(shiftDays(date, -1))} aria-label="Previous day">
          <ChevronLeft />
        </button>
        <div className="dlabel">
          <h1>{relative ?? dateLong(date)}</h1>
          {relative && <span className="muted">{dateLong(date)}</span>}
        </div>
        <button
          className="iconbtn"
          onClick={() => onDate(shiftDays(date, 1))}
          disabled={date >= today}
          aria-label="Next day"
        >
          <ChevronRight />
        </button>
        <span className="grow" />
        <input
          className="input datepick"
          type="date"
          aria-label="Pick a day"
          value={date}
          max={today}
          onChange={(e) => e.target.value && onDate(e.target.value > today ? today : e.target.value)}
        />
        {date !== today && (
          <button className="btn ghost small" onClick={() => onDate(today)}>Today</button>
        )}
      </div>

      <Loadable state={day} skeleton={<DaySkeleton />}>
        {(data) => <DayBody d={data} onTrace={onTrace} />}
      </Loadable>
    </>
  );
}

function DaySkeleton() {
  return (
    <>
      <section className="card hero"><Skeleton rows={2} /></section>
      <section className="card"><Skeleton rows={5} lead={false} /></section>
    </>
  );
}

function DayBody({ d, onTrace }: { d: DayView; onTrace?: (segmentId: string) => void }) {
  const empty = d.totals.screenMinutes === 0 && d.hours.every((h) => h.byCategory.every((c) => c.minutes === 0));
  const categoryName = new Map(d.byCategory.map((c) => [c.categoryId, c.name]));
  const work = d.byCategory.find((c) => c.name === 'Work');

  return (
    <>
      {d.derivation.rebuilding && (
        <Notice tone="info" title={`Recalculating · ${Math.round(d.derivation.progress * 100)}%`}>
          The rules changed and past days are being rebuilt. Numbers may move until it finishes.
          <Progress value={d.derivation.progress} label="Recalculation progress" />
        </Notice>
      )}
      {d.state === 'arriving' && (
        <Notice tone="warn" title="Data still arriving">
          A device has not caught up with this day yet. The numbers can still grow.
        </Notice>
      )}

      <Headline d={d} />

      {empty ? (
        <Card>
          <Empty title="No screen time recorded on this day.">
            {d.state === 'closed'
              ? 'Every device reported and none had its screen in use.'
              : 'Nothing has arrived for this day yet.'}
          </Empty>
        </Card>
      ) : (
        <>
          <Card title="By hour" hint={`${duration(d.totals.screenMinutes)} in total`}>
            <HourlyChart hours={d.hours} categories={d.byCategory} onTrace={onTrace} />
          </Card>

          <div className="grid2">
            <Card title="By category" hint={work ? `Work ${duration(work.minutes)} · rest ${duration(d.totals.screenMinutes - work.minutes)}` : undefined}>
              <BarList
                total={d.totals.screenMinutes}
                items={d.byCategory
                  .filter((c) => c.minutes > 0)
                  .map((c) => ({ id: c.categoryId, name: c.name, color: safeColor(c.color), minutes: c.minutes }))}
              />
              <p className="help">
                Category total <span className="num" data-testid="category-sum">
                  {duration(d.byCategory.reduce((s, c) => s + c.minutes, 0))}
                </span>. Time used on two devices at once is counted once, under the phone.
              </p>
            </Card>

            <Card title="By device">
              <ul className="rows devrows">
                {d.totals.byDevice.map((dev) => (
                  <li key={dev.deviceId}>
                    {dev.kind === 'android' ? <Phone size={16} /> : <Desktop size={16} />}
                    <span className="grow">{dev.name}</span>
                    <span className="num">{duration(dev.minutes)}</span>
                  </li>
                ))}
                {d.totals.overlapMinutes > 0 && (
                  <li className="muted">
                    <span className="grow">Overlap, used at the same time</span>
                    <span className="num">−{duration(d.totals.overlapMinutes)}</span>
                  </li>
                )}
                <li className="total">
                  <span className="grow">Screen time, counted once</span>
                  <span className="num" data-testid="day-total">{duration(d.totals.screenMinutes)}</span>
                </li>
              </ul>
            </Card>
          </div>

          <Card title="Top apps and sites">
            {d.topSubjects.length === 0 ? (
              <p className="help">No app or site time on this day.</p>
            ) : (
              <ul className="rows subjects">
                {d.topSubjects.map((s) => (
                  <li key={s.subjectKey}>
                    <span className="grow label">{s.label}</span>
                    <span className="muted small">{(s.categoryId && categoryName.get(s.categoryId)) || 'Uncategorized'}</span>
                    <span className="num">{duration(s.minutes)}</span>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </>
      )}

      <div className="grid2">
        <Card title="Unlocks" hint={<span className="num">{count(d.unlocks.count)}</span>}>
          {d.unlocks.firstApps.length === 0 ? (
            <p className="help">No first app recorded after an unlock.</p>
          ) : (
            <>
              <p className="help">First app opened after unlocking:</p>
              <ul className="rows">
                {d.unlocks.firstApps.map((a) => (
                  <li key={a.subjectKey}>
                    <span className="grow label">{a.label}</span>
                    <span className="num">{count(a.count)}×</span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </Card>

        <Card title="Notifications">
          {d.notifications.length === 0 ? (
            <p className="help">No notifications recorded.</p>
          ) : (
            <div className="tablewrap">
              <table>
                <thead>
                  <tr>
                    <th scope="col">App</th>
                    <th scope="col" className="r">Posted</th>
                    <th scope="col" className="r">Tapped</th>
                    <th scope="col" className="r">Dismissed</th>
                  </tr>
                </thead>
                <tbody>
                  {d.notifications.map((n) => (
                    <tr key={n.subjectKey}>
                      <td className="label">{n.label}</td>
                      <td className="r num">{count(n.posted)}</td>
                      <td className="r num">{count(n.tapped)}</td>
                      <td className="r num">{count(n.dismissed)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>
    </>
  );
}

function Headline({ d }: { d: DayView }) {
  const h = d.headline;
  const parts = durationParts(h.awayMinutes);
  return (
    <section className="card hero" aria-labelledby="headline-label">
      <div className="eyebrow" id="headline-label">Awake hours away from screens</div>
      <div className="bignum num" data-testid="headline">
        {parts.map((p) => (
          <span key={p.unit}>
            {p.value}
            <small>{p.unit}</small>
          </span>
        ))}
      </div>
      <div className="herotags">
        {h.elapsedOnly && <Badge tone="accent">In progress</Badge>}
        {h.incomplete && <Badge tone="warn">Incomplete</Badge>}
      </div>
      {h.elapsedOnly && (
        <p className="herometa">
          Counting the {duration(h.awakeMinutes)} of awake time that already passed today.
        </p>
      )}
      {!h.elapsedOnly && (
        <p className="herometa">Out of {duration(h.awakeMinutes)} awake.</p>
      )}
      {h.incomplete && (
        <p className="herometa warnline">
          {h.silentSensors.length > 0
            ? `Silent while you were awake: ${h.silentSensors.join(', ')}. The real number is probably lower.`
            : 'A sensor was silent while you were awake. The real number is probably lower.'}
        </p>
      )}
      <dl className="herolines">
        <div>
          <dt>Screen time while awake</dt>
          <dd className="num">{duration(h.screenMinutes)}</dd>
        </div>
        <div>
          <dt>Screen time outside the awake window</dt>
          <dd className="num">{duration(h.outsideAwakeScreenMinutes)}</dd>
        </div>
      </dl>
    </section>
  );
}
