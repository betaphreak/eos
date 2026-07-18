import { useState } from 'react';
import { Box, Flex, Typography, Button } from '@strapi/design-system';
import { serverFetch, type SessionRow } from '../lib/serverApi';
import { useServerPoll } from '../lib/useServerPoll';
import { CenteredLoader, Gate, ActionResult } from './opsShared';

const STATE_COLOR: Record<string, string> = {
  RUNNING: 'success600',
  PAUSED: 'warning600',
  STOPPED: 'neutral500',
  CREATED: 'neutral500',
};

/**
 * Homepage widget: the live sessions from the old admin.html "Sessions" panel, with per-session
 * pause / resume / stop. Control is a POST /api/sessions/{id}/control with a {action} body — admins
 * bypass the session ownership check on the server.
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
    <Flex direction="column" alignItems="stretch" gap={2}>
      {rows.length === 0 && (
        <Typography variant="omega" textColor="neutral600">
          No live sessions.
        </Typography>
      )}

      {rows.map((s) => (
        <Flex key={s.id} justifyContent="space-between" alignItems="center" gap={2}
          background="neutral100" hasRadius padding={2}>
          <Flex direction="column" alignItems="flex-start" gap={0} style={{ minWidth: 0 }}>
            <Typography variant="omega" fontWeight="bold" ellipsis>
              {s.id}
            </Typography>
            <Typography variant="pi" textColor={STATE_COLOR[s.state] ?? 'neutral600'}>
              {s.state}
              {s.tick != null ? ` · tick ${s.tick}` : ''}
              {s.owner ? ` · ${s.owner}` : ''}
            </Typography>
          </Flex>
          <Flex gap={1} style={{ flexShrink: 0 }}>
            <Button size="S" variant="tertiary" disabled={busyId === s.id} onClick={() => control(s.id, 'pause')}>
              Pause
            </Button>
            <Button size="S" variant="tertiary" disabled={busyId === s.id} onClick={() => control(s.id, 'resume')}>
              Resume
            </Button>
            <Button size="S" variant="danger-light" disabled={busyId === s.id} onClick={() => control(s.id, 'stop')}>
              Stop
            </Button>
          </Flex>
        </Flex>
      ))}

      <ActionResult message={result?.message ?? null} tone={result?.tone ?? 'success'} />
    </Flex>
  );
}
