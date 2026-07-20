import { Flex, Table, Thead, Tbody, Tr, Th, Td, Typography } from '@strapi/design-system';
import { serverFetch, type CommandLogView } from '../../lib/serverApi';
import { useServerPoll } from '../../lib/useServerPoll';
import { CenteredLoader, Gate } from '../opsShared';
import { Detail, Empty, LoadError, Section } from '../sessionBits';

/**
 * The session's command log — GET /api/sessions/{sid}/commands (added in plan §C1; before that it
 * was reachable only through the MCP get_command_log tool).
 *
 * This is the one session read the server GATES: spectating is public, but the record of what an
 * owner did is theirs, so a non-owner gets a 401/403 here and the sign-in gate renders instead. That
 * is expected, not a failure.
 */
export default function CommandsPanel({ sessionId }: { sessionId: string }) {
  const { data, loading, gate, error } = useServerPoll<CommandLogView>(() =>
    serverFetch('GET', `/api/sessions/${sessionId}/commands`),
  );

  if (gate) return <Gate status={gate} />;
  if (loading && !data) return <CenteredLoader />;
  // Without this the panel reports "Applied 0 / No commands" for a request that failed — which is
  // exactly what it did against a server predating this endpoint (405). An empty replay log is a
  // meaningful claim about the run; never make it on a read that did not happen.
  if (error && !data) return <LoadError message={error} />;

  const history = data?.history ?? [];

  return (
    <Flex direction="column" alignItems="stretch" gap={4}>
      <Section title="Replay log">
        <Flex gap={4} wrap="wrap">
          <Detail label="Applied" value={history.length} />
          <Detail label="In flight" value={data?.pending ?? 0} />
        </Flex>
        <Typography variant="pi" textColor="neutral500">
          A run has no savegame file: replaying its seed, scenario and province while applying these
          commands in order reproduces it exactly. Empty is normal during pure spectator play.
        </Typography>
      </Section>

      <Section title={`Commands (${history.length})`}>
        {history.length === 0 ? (
          <Empty>No commands have been applied to this run.</Empty>
        ) : (
          <Table colCount={4} rowCount={history.length}>
            <Thead>
              <Tr>
                <Th><Typography variant="sigma">Tick</Typography></Th>
                <Th><Typography variant="sigma">Type</Typography></Th>
                <Th><Typography variant="sigma">Lever</Typography></Th>
                <Th><Typography variant="sigma">Rate</Typography></Th>
              </Tr>
            </Thead>
            <Tbody>
              {history.map((c, i) => (
                <Tr key={`${c.tick}-${i}`}>
                  <Td>
                    <Typography textColor="neutral700"
                      style={{ fontVariantNumeric: 'tabular-nums' }}>
                      {c.tick.toLocaleString()}
                    </Typography>
                  </Td>
                  <Td><Typography textColor="neutral800">{c.type}</Typography></Td>
                  <Td><Typography textColor="neutral700">{c.lever ?? '—'}</Typography></Td>
                  <Td>
                    <Typography textColor="neutral700">
                      {c.rate == null ? '—' : `${(c.rate * 100).toFixed(1)}%`}
                    </Typography>
                  </Td>
                </Tr>
              ))}
            </Tbody>
          </Table>
        )}
      </Section>
    </Flex>
  );
}
