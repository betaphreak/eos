import { useState } from 'react';
import { Box, Button, Flex, Searchbar, SingleSelect, SingleSelectOption, Typography } from '@strapi/design-system';
import { serverFetch, type LogLine } from '../../lib/serverApi';
import { useServerPoll } from '../../lib/useServerPoll';
import { CenteredLoader, Gate } from '../opsShared';
import { Empty, LoadError, Section } from '../sessionBits';

const SEV_COLOR: Record<string, string> = {
  error: 'danger600',
  warn: 'warning600',
  info: 'neutral700',
};

/**
 * The run's event log — GET /api/sessions/{sid}/events, with the server's own level / substring
 * filters rather than filtering client-side, so the tail stays the authority on what a line is.
 *
 * This is deliberately NOT the snapshot's `log`: that field is a drain-once DELTA, so a stopped run
 * would hand back the same one or two lines forever. The tail is the retained ring (4096 lines).
 */
export default function EventsPanel({ sessionId }: { sessionId: string }) {
  const [grep, setGrep] = useState('');
  const [level, setLevel] = useState<string>('');
  const [limit, setLimit] = useState(200);

  const params = new URLSearchParams();
  if (grep) params.set('grep', grep);
  if (level) params.set('level', level);
  params.set('limit', String(limit));

  const { data, loading, gate, error, reload } = useServerPoll<LogLine[]>(() =>
    serverFetch('GET', `/api/sessions/${sessionId}/events?${params.toString()}`),
  );

  if (gate) return <Gate status={gate} />;

  const lines = Array.isArray(data) ? data : [];

  return (
    <Section
      title={`Events${lines.length ? ` (${lines.length})` : ''}`}
      action={
        <Flex gap={2} alignItems="center">
          <Box style={{ width: 140 }}>
            <SingleSelect aria-label="Severity" placeholder="Any severity" value={level}
              onClear={() => setLevel('')} onChange={(v) => setLevel(String(v ?? ''))}>
              <SingleSelectOption value="info">Info</SingleSelectOption>
              <SingleSelectOption value="warn">Warn</SingleSelectOption>
              <SingleSelectOption value="error">Error</SingleSelectOption>
            </SingleSelect>
          </Box>
          <Box style={{ width: 220 }}>
            <Searchbar name="grep" value={grep} clearLabel="Clear"
              onClear={() => setGrep('')}
              onChange={(e: React.ChangeEvent<HTMLInputElement>) => setGrep(e.target.value)}
              placeholder="Contains…">
              Search the log
            </Searchbar>
          </Box>
          <Button size="S" variant="tertiary" onClick={() => reload()}>Refresh</Button>
        </Flex>
      }
    >
      {loading && !data ? (
        <CenteredLoader />
      ) : error && !data ? (
        // "no lines match" is a claim about the log; don't make it when the read failed
        <LoadError message={error} />
      ) : lines.length === 0 ? (
        <Empty>No lines match.</Empty>
      ) : (
        <Flex direction="column" alignItems="stretch" gap={0}
          style={{ maxHeight: 560, overflowY: 'auto' }}>
          {lines.map((l, i) => (
            <Flex key={i} gap={3} alignItems="baseline" padding={2}
              background={i % 2 ? 'neutral100' : undefined} hasRadius>
              <Typography variant="pi" textColor="neutral500"
                style={{ flexShrink: 0, fontVariantNumeric: 'tabular-nums' }}>
                {l.date}
              </Typography>
              <Typography variant="pi" textColor={SEV_COLOR[l.sev] ?? 'neutral700'}
                fontWeight={l.curated ? 'bold' : undefined}>
                {l.text}
              </Typography>
            </Flex>
          ))}
          {lines.length >= limit && (
            <Box paddingTop={2}>
              <Button size="S" variant="tertiary" onClick={() => setLimit((n) => Math.min(512, n + 200))}>
                Show more (server caps the tail at 512)
              </Button>
            </Box>
          )}
        </Flex>
      )}
    </Section>
  );
}
