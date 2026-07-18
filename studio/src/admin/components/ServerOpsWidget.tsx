import { useState } from 'react';
import { Box, Flex, Typography, Button, Divider } from '@strapi/design-system';
import { serverFetch, serverBase, formatDuration, type ServerStatus } from '../lib/serverApi';
import { useServerPoll } from '../lib/useServerPoll';
import { CenteredLoader, Gate, Stat, ActionResult } from './opsShared';

/**
 * Homepage widget: live server status + the destructive ops from the old admin.html "Server" and
 * "Plot cache" panels (drop the plot cache, clear lobby chat). Calls the game server's gated
 * /api/admin/** cross-origin with the operator's server session (see lib/serverApi).
 */
export default function ServerOpsWidget() {
  const { data, loading, gate, reload } = useServerPoll<ServerStatus>(() =>
    serverFetch('GET', '/api/admin/status'),
  );
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{ message: string; tone: 'success' | 'danger' } | null>(null);

  if (gate) return <Gate status={gate} />;
  if (loading && !data) return <CenteredLoader />;
  if (!data) return <Gate status={403} />;

  const { server: sv, plots: pl } = data;

  async function run(action: () => Promise<string>, confirmMsg?: string) {
    if (confirmMsg && !window.confirm(confirmMsg)) return;
    setBusy(true);
    setResult(null);
    try {
      setResult({ message: await action(), tone: 'success' });
    } catch (e) {
      setResult({ message: e instanceof Error ? e.message : String(e), tone: 'danger' });
    } finally {
      setBusy(false);
      reload();
    }
  }

  const dropPlots = () =>
    run(async () => {
      const r = await serverFetch<{ cleared: number }>('POST', '/api/admin/plots/clear');
      return `Dropped ${r.cleared} cached plot grids.`;
    }, 'Drop the entire plot cache? Every province regenerates on demand (and loses place names until the next CI bake).');

  const clearChat = () =>
    run(async () => {
      await serverFetch('POST', '/api/admin/chat/clear');
      return 'Lobby chat cleared.';
    }, 'Clear all lobby chat history?');

  return (
    <Flex direction="column" alignItems="stretch" gap={3}>
      <Flex gap={2} wrap="wrap">
        <Stat label="Uptime" value={formatDuration(sv.uptimeMs)} />
        <Stat label="Heap" value={`${sv.heapUsedMb}/${sv.heapMaxMb} MB`} />
        <Stat label="Sessions" value={sv.sessions} />
        <Stat label="Admins" value={sv.admins} />
        <Stat label="Plots" value={`${pl.cached}/${pl.total}`} emphasis />
        <Stat label="Map" value={`v${pl.mapVersion}`} emphasis />
      </Flex>

      <Divider />

      <Flex gap={2} wrap="wrap" alignItems="center">
        <Button variant="danger-light" onClick={dropPlots} loading={busy} disabled={busy}>
          Drop plot cache
        </Button>
        <Button variant="danger-light" onClick={clearChat} loading={busy} disabled={busy}>
          Clear lobby chat
        </Button>
        {pl.storageUrl && (
          <Button variant="tertiary" onClick={() => window.open(pl.storageUrl, '_blank', 'noopener')}>
            Storage Explorer ↗
          </Button>
        )}
      </Flex>

      <ActionResult message={result?.message ?? null} tone={result?.tone ?? 'success'} />

      <Typography variant="pi" textColor="neutral500">
        {serverBase.replace(/^https?:\/\//, '')}
        {sv.you ? ` · ${sv.you}` : ''}
      </Typography>
    </Flex>
  );
}
