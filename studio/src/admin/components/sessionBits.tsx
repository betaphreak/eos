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

/** A titled card — the unit every detail panel is built from. */
export function Section({ title, action, children }:
    { title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <Box background="neutral0" hasRadius shadow="tableShadow" padding={5}>
      <Flex direction="column" alignItems="stretch" gap={3}>
        <Flex justifyContent="space-between" alignItems="center" gap={2}>
          <Typography variant="delta" textColor="neutral800">
            {title}
          </Typography>
          {action}
        </Flex>
        {children}
      </Flex>
    </Box>
  );
}

/**
 * One skill as a labelled bar. Skill levels are 0..20 (the RimWorld-style ladder), so the bar is
 * scaled to 20 rather than to the row maximum — a colony whose best skill is 6 should *look* unskilled
 * rather than have its 6 fill the width.
 */
export function SkillBar({ label, value, note }: { label: string; value: number; note?: ReactNode }) {
  const pct = Math.max(0, Math.min(100, (value / 20) * 100));
  return (
    <Flex direction="column" alignItems="stretch" gap={1}>
      <Flex justifyContent="space-between" alignItems="baseline" gap={2}>
        <Typography variant="pi" textColor="neutral700" style={{ textTransform: 'capitalize' }}>
          {label.replace(/_/g, ' ').toLowerCase()}
        </Typography>
        <Typography variant="pi" fontWeight="bold" textColor="neutral800">
          {value.toFixed(1)}
          {note ? ' ' : ''}
          {note}
        </Typography>
      </Flex>
      <Box background="neutral150" hasRadius style={{ height: 6, overflow: 'hidden' }}>
        <Box background="primary600" style={{ height: '100%', width: `${pct}%` }} />
      </Box>
    </Flex>
  );
}

/** An empty-state line for a panel with nothing to show. */
export function Empty({ children }: { children: ReactNode }) {
  return (
    <Typography variant="omega" textColor="neutral600">
      {children}
    </Typography>
  );
}

/**
 * A panel whose fetch failed.
 *
 * This exists because the alternative is worse than an error: without it a failed request leaves
 * `data` null and the panel renders its EMPTY state, so "the server said no" is indistinguishable
 * from "there is nothing here" — a panel confidently reporting zero commands for a request that
 * 405'd. Always prefer saying the read failed.
 */
export function LoadError({ message }: { message: string }) {
  return (
    <Flex direction="column" alignItems="flex-start" gap={1}>
      <Typography variant="omega" textColor="danger600">
        Could not read this from the game server.
      </Typography>
      <Typography variant="pi" textColor="neutral600">
        {message}
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
