import { useState, type ReactNode } from 'react';
import { Box, Flex, Typography, Button } from '@strapi/design-system';
import { serverFetch, type SessionRow } from '../lib/serverApi';
import { useServerPoll } from '../lib/useServerPoll';
import { CenteredLoader, Gate, ActionResult } from './opsShared';

// The two control axes are coloured independently: clockState is the transport (is it ticking?),
// outcome the verdict (is it decided?). See ClockState / Outcome on the server.
const CLOCK_COLOR: Record<string, string> = {
  RUNNING: 'success600',
  PAUSED: 'warning600',
  STOPPED: 'neutral500',
  CREATED: 'neutral500',
};
const OUTCOME_COLOR: Record<string, string> = {
  LIVE: 'success600',
  WON: 'primary600',
  LOST: 'danger600',
  ABANDONED: 'warning600',
};
const KIND_LABEL: Record<string, string> = {
  demo: 'Demo',
  'single-player': 'Single-player',
  multiplayer: 'Multiplayer',
  timeline: 'Timeline',
};

/** A small inline pill (kind / "mine"). Hand-rolled so we don't depend on Badge's prop shape. */
function Pill({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'neutral' | 'primary' }) {
  return (
    <Box
      background={tone === 'primary' ? 'primary100' : 'neutral150'}
      hasRadius
      paddingLeft={2}
      paddingRight={2}
      style={{ flexShrink: 0 }}
    >
      <Typography variant="pi" textColor={tone === 'primary' ? 'primary600' : 'neutral700'}>
        {children}
      </Typography>
    </Box>
  );
}

/** One label/value pair in the detail grid. */
function Detail({ label, value }: { label: string; value: ReactNode }) {
  return (
    <Flex direction="column" alignItems="flex-start" gap={0} style={{ minWidth: 0 }}>
      <Typography variant="pi" textColor="neutral600">
        {label}
      </Typography>
      <Typography variant="pi" fontWeight="bold" textColor="neutral800" ellipsis>
        {value}
      </Typography>
    </Flex>
  );
}

/**
 * Homepage widget: the live sessions from the old admin.html "Sessions" panel, with per-session
 * pause / resume / stop. Control is a POST /api/sessions/{id}/control with a {action} body — admins
 * bypass the session ownership check on the server.
 *
 * The row renders the full lobby payload (see SessionRow): identity + clock/outcome + the run's
 * parameters (scenario, seed, realm) + live figures (in-game date, tick, spectators), plus the
 * Timeline-only standing/seats and the endReason once a run is decided.
 */
export default function SessionsWidget() {
  const { data, loading, gate, reload } = useServerPoll<SessionRow[] | { sessions?: SessionRow[] }>(() =>
    serverFetch('GET', '/api/sessions'),
  );
  const [busyId, setBusyId] = useState<string | null>(null);
  const [result, setResult] = useState<{ message: string; tone: 'success' | 'danger' } | null>(null);

  if (gate) return <Gate status={gate} />;
  if (loading && !data) return <CenteredLoader />;

  const rows: SessionRow[] = Array.isArray(data) ? data : (data?.sessions ?? []);

  async function control(id: string, action: 'pause' | 'resume' | 'stop') {
    if (action === 'stop' && !window.confirm(`Stop session ${id}? (it can be re-founded)`)) return;
    setBusyId(id);
    setResult(null);
    try {
      await serverFetch('POST', `/api/sessions/${id}/control`, { action });
      setResult({ message: `Session ${id}: ${action}`, tone: 'success' });
    } catch (e) {
      setResult({ message: e instanceof Error ? e.message : String(e), tone: 'danger' });
    } finally {
      setBusyId(null);
      reload();
    }
  }

  return (
    // Cap the height so a busy server can't stretch the homepage grid — the list scrolls instead.
    <Flex direction="column" alignItems="stretch" gap={2} style={{ maxHeight: 420, overflowY: 'auto' }}>
      {rows.length === 0 && (
        <Typography variant="omega" textColor="neutral600">
          No live sessions.
        </Typography>
      )}

      {rows.map((s) => {
        const clock = s.clockState ?? 'CREATED';
        const outcome = s.outcome ?? 'LIVE';
        const busy = busyId === s.id;
        // What the server will actually accept (SessionController#control):
        //  - a DECIDED run (outcome != LIVE) takes no orders at all — it 409s "session is over";
        //  - a merely STOPPED run is NOT finished: getOrRestore revives it, so resume still works.
        // So gate on the outcome, and past that only grey out the exact no-ops.
        const decided = outcome !== 'LIVE';
        const canPause = !decided && clock !== 'PAUSED' && clock !== 'STOPPED';
        const canResume = !decided && clock !== 'RUNNING';
        const canStop = !decided && clock !== 'STOPPED';

        return (
          <Flex
            key={s.id}
            direction="column"
            alignItems="stretch"
            gap={2}
            background="neutral100"
            hasRadius
            padding={3}
          >
            {/* identity: colony name is the title, id the subtitle (id alone when unnamed) */}
            <Flex justifyContent="space-between" alignItems="flex-start" gap={2}>
              <Flex direction="column" alignItems="flex-start" gap={0} style={{ minWidth: 0 }}>
                <Typography variant="omega" fontWeight="bold" ellipsis>
                  {s.colony ?? s.id}
                </Typography>
                {s.colony && (
                  <Typography variant="pi" textColor="neutral600" ellipsis>
                    {s.id}
                  </Typography>
                )}
              </Flex>
              <Flex gap={1} style={{ flexShrink: 0 }}>
                {s.mine && <Pill tone="primary">Mine</Pill>}
                {s.kind && <Pill>{KIND_LABEL[s.kind] ?? s.kind}</Pill>}
              </Flex>
            </Flex>

            {/* the two control axes */}
            <Flex gap={1} alignItems="center">
              <Typography variant="pi" fontWeight="bold" textColor={CLOCK_COLOR[clock] ?? 'neutral600'}>
                {clock}
              </Typography>
              <Typography variant="pi" textColor="neutral500">
                ·
              </Typography>
              <Typography variant="pi" fontWeight="bold" textColor={OUTCOME_COLOR[outcome] ?? 'neutral600'}>
                {outcome}
              </Typography>
            </Flex>

            {/* the run's live figures + parameters */}
            <Flex gap={4} wrap="wrap">
              {s.date && <Detail label="In-game date" value={s.date} />}
              {s.tick != null && <Detail label="Tick" value={s.tick.toLocaleString()} />}
              {s.watching != null && <Detail label="Watching" value={s.watching} />}
              {s.standing != null && <Detail label="Standing" value={`${s.standing}/${s.seats ?? '?'}`} />}
              {s.scenario && <Detail label="Scenario" value={s.scenario} />}
              {s.seed != null && <Detail label="Seed" value={s.seed} />}
              {s.realm && <Detail label="Realm" value={s.realm} />}
            </Flex>

            {s.endReason && (
              <Typography variant="pi" textColor="neutral600" style={{ fontStyle: 'italic' }}>
                {s.endReason}
              </Typography>
            )}

            <Flex gap={1} justifyContent="flex-end">
              <Button size="S" variant="tertiary" disabled={busy || !canPause} onClick={() => control(s.id, 'pause')}>
                Pause
              </Button>
              <Button size="S" variant="tertiary" disabled={busy || !canResume} onClick={() => control(s.id, 'resume')}>
                Resume
              </Button>
              <Button size="S" variant="danger-light" disabled={busy || !canStop} onClick={() => control(s.id, 'stop')}>
                Stop
              </Button>
            </Flex>
          </Flex>
        );
      })}

      <ActionResult message={result?.message ?? null} tone={result?.tone ?? 'success'} />
    </Flex>
  );
}
