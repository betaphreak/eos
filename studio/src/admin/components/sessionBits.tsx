import type { ReactNode } from 'react';
import { Box, Flex, Typography } from '@strapi/design-system';
import { CLOCK_COLOR, OUTCOME_COLOR } from '../lib/sessions';
import type { SessionRow } from '../lib/serverApi';

/** A small inline pill (kind / "mine"). Hand-rolled so we don't depend on Badge's prop shape. */
export function Pill({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'neutral' | 'primary' }) {
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

/** One label/value pair in a detail grid. */
export function Detail({ label, value }: { label: string; value: ReactNode }) {
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

/** The two control axes, coloured independently: clock (is it ticking?) · outcome (is it decided?). */
export function StatePair({ session }: { session: SessionRow }) {
  const clock = session.clockState ?? 'CREATED';
  const outcome = session.outcome ?? 'LIVE';
  return (
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
  );
}

/** The run's live figures + parameters, as a wrapping grid. */
export function SessionFigures({ session }: { session: SessionRow }) {
  const s = session;
  return (
    <Flex gap={4} wrap="wrap">
      {s.date && <Detail label="In-game date" value={s.date} />}
      {s.tick != null && <Detail label="Tick" value={s.tick.toLocaleString()} />}
      {s.watching != null && <Detail label="Watching" value={s.watching} />}
      {s.standing != null && <Detail label="Standing" value={`${s.standing}/${s.seats ?? '?'}`} />}
      {s.scenario && <Detail label="Scenario" value={s.scenario} />}
      {s.seed != null && <Detail label="Seed" value={s.seed} />}
      {s.realm && <Detail label="Realm" value={s.realm} />}
    </Flex>
  );
}
