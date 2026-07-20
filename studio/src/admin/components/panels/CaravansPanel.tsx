import { useState } from 'react';
import { Box, Button, Flex, Typography } from '@strapi/design-system';
import { serverFetch, type CaravanDetail, type CaravanView } from '../../lib/serverApi';
import { useServerPoll } from '../../lib/useServerPoll';
import { CenteredLoader } from '../opsShared';
import { Detail, Empty, LoadError, Pill, Section, SkillBar } from '../sessionBits';

/**
 * The run's bands. The LIST comes from the snapshot (the only place caravans are enumerated — there
 * is no /caravans route); selecting one fetches GET /api/sessions/{sid}/caravan/{id} for its crew.
 *
 * The crew is served already sorted by survival, which is the band's <b>succession order</b> — the
 * order in which they would inherit the lead — so it is rendered in the order given rather than
 * re-sorted by anything prettier.
 */
export default function CaravansPanel({ sessionId, caravans }:
    { sessionId: string; caravans: CaravanView[] }) {
  const [selected, setSelected] = useState<number | null>(null);

  if (!caravans.length)
    return <Empty>No bands are afoot. Colonies muster their foraging levies emergently, over winter.</Empty>;

  return (
    <Flex direction="column" alignItems="stretch" gap={4}>
      <Section title={`Bands (${caravans.length})`}>
        <Flex direction="column" alignItems="stretch" gap={2}>
          {caravans.map((c) => (
            <Box key={c.id} background={selected === c.id ? 'primary100' : 'neutral100'}
              hasRadius padding={3}>
              <Flex justifyContent="space-between" alignItems="center" gap={2}>
                <Flex direction="column" alignItems="flex-start" gap={0} style={{ minWidth: 0 }}>
                  <Flex gap={2} alignItems="center">
                    <Typography variant="omega" fontWeight="bold" textColor="neutral800" ellipsis>
                      {c.label || c.unitName || `Band ${c.id}`}
                    </Typography>
                    {c.role && <Pill>{c.role}</Pill>}
                    {c.settled && <Pill tone="primary">Settled</Pill>}
                  </Flex>
                  <Typography variant="pi" textColor="neutral600" ellipsis>
                    led by {c.leader} · {c.onGraph ? c.province : 'off the road network'}
                  </Typography>
                </Flex>
                <Button size="S" variant="tertiary" style={{ flexShrink: 0 }}
                  onClick={() => setSelected(selected === c.id ? null : c.id)}>
                  {selected === c.id ? 'Hide' : 'Crew'}
                </Button>
              </Flex>
              <Box paddingTop={2}>
                <Flex gap={4} wrap="wrap">
                  <Detail label="Band size" value={c.bandSize} />
                  <Detail label="Larder" value={c.larder.toFixed(1)} />
                  <Detail label="Hoard" value={c.hoard.toFixed(1)} />
                  {c.signatureSkill && <Detail label="Signature" value={c.signatureSkill} />}
                </Flex>
              </Box>
            </Box>
          ))}
        </Flex>
      </Section>

      {selected != null && <CrewSection sessionId={sessionId} id={selected} />}
    </Flex>
  );
}

function CrewSection({ sessionId, id }: { sessionId: string; id: number }) {
  const { data, loading, error } = useServerPoll<CaravanDetail>(() =>
    serverFetch('GET', `/api/sessions/${sessionId}/caravan/${id}`),
  );

  if (loading && !data) return <CenteredLoader />;
  // "that band is gone" would be a lie if we simply failed to ask
  if (error && !data) return <LoadError message={error} />;
  if (!data?.leader) return <Empty>That band is gone.</Empty>;

  const skills = [...(data.skills ?? [])].sort((a, b) => b.avg - a.avg);

  return (
    <Flex direction="column" alignItems="stretch" gap={4}>
      <Section title={`${data.unitName ?? 'Band'} — skill profile`}>
        {skills.length === 0 ? (
          <Empty>No skills to average.</Empty>
        ) : (
          <Flex direction="column" alignItems="stretch" gap={2}>
            {skills.map((s) => <SkillBar key={s.skill} label={s.skill} value={s.avg} />)}
          </Flex>
        )}
      </Section>

      <Section title={`Crew (${data.members?.length ?? 0}) — in succession order`}>
        <Flex direction="column" alignItems="stretch" gap={1}>
          {(data.members ?? []).map((m, i) => (
            <Flex key={`${m.name}-${i}`} justifyContent="space-between" alignItems="center"
              gap={2} background={i % 2 ? 'neutral100' : undefined} hasRadius padding={2}>
              <Flex gap={2} alignItems="center" style={{ minWidth: 0 }}>
                <Typography variant="omega" textColor="neutral800"
                  fontWeight={m.leader ? 'bold' : undefined} ellipsis>
                  {m.name}
                </Typography>
                {m.leader && <Pill tone="primary">Leader</Pill>}
              </Flex>
              <Typography variant="pi" textColor="neutral600" style={{ flexShrink: 0 }}>
                {m.race} · {m.age} · survival {m.survival}
              </Typography>
            </Flex>
          ))}
        </Flex>
      </Section>
    </Flex>
  );
}
