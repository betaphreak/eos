import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Box, Flex, Typography, Button } from '@strapi/design-system';
import { ArrowLeft } from '@strapi/icons';
import { Layouts, Page } from '@strapi/admin/strapi-admin';
import { serverFetch, type SessionRow } from '../lib/serverApi';
import { useServerPoll } from '../lib/useServerPoll';
import { KIND_LABEL, controlsFor, controlSession, sessionTitle, type ControlAction } from '../lib/sessions';
import { CenteredLoader, Gate, ActionResult } from '../components/opsShared';
import { Pill, StatePair, SessionFigures } from '../components/sessionBits';

/**
 * One run, in full — the page the "Live sessions" widget and the list link into.
 *
 * <b>Shell + Overview only</b> (plan §C2). The richer panels — colony composition, caravans, the
 * court, the event log and the command log — are §C3; they slot in below the Overview card, each
 * reading an endpoint that already exists (`/colony`, `/caravan/{id}`, `/person/{id}`, `/events`,
 * `/commands`).
 *
 * The run is found by filtering the lobby list rather than by a per-session endpoint: `GET
 * /api/sessions` is the only route that returns the lobby row shape, and it already applies the
 * caller's visibility rules, so a run the operator may not see simply is not here.
 */
export default function SessionDetailPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{ message: string; tone: 'success' | 'danger' } | null>(null);

  const { data, loading, gate, reload } = useServerPoll<SessionRow[] | { sessions?: SessionRow[] }>(() =>
    serverFetch('GET', '/api/sessions'),
  );

  const rows: SessionRow[] = Array.isArray(data) ? data : (data?.sessions ?? []);
  const session = rows.find((s) => s.id === id);

  async function control(action: ControlAction) {
    if (action === 'stop' && !window.confirm(`Stop session ${id}? (it can be re-founded)`)) return;
    setBusy(true);
    setResult(null);
    try {
      await controlSession(id, action);
      setResult({ message: `Session ${id}: ${action}`, tone: 'success' });
    } catch (e) {
      setResult({ message: e instanceof Error ? e.message : String(e), tone: 'danger' });
    } finally {
      setBusy(false);
      reload();
    }
  }

  const back = (
    <Button variant="tertiary" startIcon={<ArrowLeft />} onClick={() => navigate('/civstudio-sessions')}>
      Sessions
    </Button>
  );

  if (gate) {
    return (
      <Page.Main>
        <Layouts.Header title={id} navigationAction={back} />
        <Layouts.Content>
          <Gate status={gate} />
        </Layouts.Content>
      </Page.Main>
    );
  }

  if (loading && !data) {
    return (
      <Page.Main>
        <Layouts.Header title={id} navigationAction={back} />
        <Layouts.Content>
          <CenteredLoader />
        </Layouts.Content>
      </Page.Main>
    );
  }

  if (!session) {
    // either no such run, or one this operator may not see — GET /api/sessions already filtered it,
    // and saying which of the two it is would leak the existence of someone else's private run
    return (
      <Page.Main>
        <Page.Title>{id}</Page.Title>
        <Layouts.Header title={id} navigationAction={back} />
        <Layouts.Content>
          <Typography variant="omega" textColor="neutral600">
            No session <b>{id}</b> is visible to you.
          </Typography>
        </Layouts.Content>
      </Page.Main>
    );
  }

  const can = controlsFor(session);

  return (
    <Page.Main>
      <Page.Title>{sessionTitle(session)}</Page.Title>
      <Layouts.Header
        title={sessionTitle(session)}
        subtitle={session.id}
        navigationAction={back}
        primaryAction={
          <Flex gap={1}>
            <Button variant="tertiary" disabled={busy || !can.pause} onClick={() => control('pause')}>
              Pause
            </Button>
            <Button variant="tertiary" disabled={busy || !can.resume} onClick={() => control('resume')}>
              Resume
            </Button>
            <Button variant="danger-light" disabled={busy || !can.stop} onClick={() => control('stop')}>
              Stop
            </Button>
          </Flex>
        }
      />
      <Layouts.Content>
        <Flex direction="column" alignItems="stretch" gap={4}>
          <Box background="neutral0" hasRadius shadow="tableShadow" padding={5}>
            <Flex direction="column" alignItems="stretch" gap={3}>
              <Flex justifyContent="space-between" alignItems="center" gap={2}>
                <StatePair session={session} />
                <Flex gap={1} style={{ flexShrink: 0 }}>
                  {session.mine && <Pill tone="primary">Mine</Pill>}
                  {session.kind && <Pill>{KIND_LABEL[session.kind] ?? session.kind}</Pill>}
                </Flex>
              </Flex>
              <SessionFigures session={session} />
              {session.endReason && (
                <Typography variant="pi" textColor="neutral600" style={{ fontStyle: 'italic' }}>
                  {session.endReason}
                </Typography>
              )}
              <ActionResult message={result?.message ?? null} tone={result?.tone ?? 'success'} />
            </Flex>
          </Box>
        </Flex>
      </Layouts.Content>
    </Page.Main>
  );
}
