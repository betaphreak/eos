import { useState } from 'react';
import { Box, Button, Flex, Typography } from '@strapi/design-system';
import { serverFetch, type AdvisorView, type PersonDetail } from '../../lib/serverApi';
import { useServerPoll } from '../../lib/useServerPoll';
import { CenteredLoader } from '../opsShared';
import { Detail, Empty, LoadError, Pill, Section, SkillBar } from '../sessionBits';

const PASSION_MARK: Record<string, string> = { minor: '+', major: '++' };

/**
 * The privy council — the advisors seated in this colony, from the snapshot's ColonyView.advisors
 * (unfilled roles are simply absent). Selecting one fetches its character sheet,
 * GET /api/sessions/{sid}/person/{id}, which carries the 12 skills with their passions and the
 * person's household.
 *
 * The person lookup is colony-scoped on purpose: agent ids are unique only WITHIN a colony, so the
 * ?colony= param must follow the advisor, or a Timeline's non-first seat would resolve the id
 * against the wrong colony's people.
 */
export default function CourtPanel({ sessionId, colony, advisors }:
    { sessionId: string; colony?: string; advisors: AdvisorView[] }) {
  const [selected, setSelected] = useState<number | null>(null);

  if (!advisors.length)
    return <Empty>No advisors are seated. Roles fill as the colony raises nobles able to hold them.</Empty>;

  return (
    <Flex direction="column" alignItems="stretch" gap={4}>
      <Section title={`Court (${advisors.length})`}>
        <Flex direction="column" alignItems="stretch" gap={2}>
          {advisors.map((a) => (
            <Flex key={a.role} justifyContent="space-between" alignItems="center" gap={2}
              background={selected === a.personId ? 'primary100' : 'neutral100'} hasRadius padding={3}>
              <Flex direction="column" alignItems="flex-start" gap={0} style={{ minWidth: 0 }}>
                <Flex gap={2} alignItems="center">
                  <Typography variant="omega" fontWeight="bold" textColor="neutral800" ellipsis>
                    {a.name}
                  </Typography>
                  <Pill>{a.role.replace(/[-_]/g, ' ')}</Pill>
                </Flex>
                <Typography variant="pi" textColor="neutral600" ellipsis>
                  {a.race}
                  {a.culture ? ` · ${a.culture}` : ''}
                </Typography>
              </Flex>
              <Button size="S" variant="tertiary" style={{ flexShrink: 0 }}
                onClick={() => setSelected(selected === a.personId ? null : a.personId)}>
                {selected === a.personId ? 'Hide' : 'Sheet'}
              </Button>
            </Flex>
          ))}
        </Flex>
      </Section>

      {selected != null && <PersonSection sessionId={sessionId} colony={colony} id={selected} />}
    </Flex>
  );
}

function PersonSection({ sessionId, colony, id }:
    { sessionId: string; colony?: string; id: number }) {
  const q = colony ? `?colony=${encodeURIComponent(colony)}` : '';
  const { data, loading, error } = useServerPoll<PersonDetail>(() =>
    serverFetch('GET', `/api/sessions/${sessionId}/person/${id}${q}`),
  );

  if (loading && !data) return <CenteredLoader />;
  // "that person is gone" would be a lie if we simply failed to ask
  if (error && !data) return <LoadError message={error} />;
  if (!data?.name) return <Empty>That person is gone.</Empty>;

  const skills = [...(data.skills ?? [])].sort((a, b) => b.level - a.level);

  return (
    <Flex direction="column" alignItems="stretch" gap={4}>
      <Section title={data.name}>
        <Flex gap={4} wrap="wrap">
          <Detail label="Role" value={data.role} />
          <Detail label="Age" value={data.ageYears} />
          <Detail label="Race" value={data.race} />
          <Detail label="Gender" value={data.gender} />
          {data.culture && <Detail label="Culture" value={data.culture} />}
        </Flex>
      </Section>

      <Section title="Skills">
        <Flex direction="column" alignItems="stretch" gap={2}>
          {skills.map((s) => (
            <SkillBar key={s.skill} label={s.skill} value={s.level}
              note={PASSION_MARK[s.passion] ?? ''} />
          ))}
        </Flex>
      </Section>

      <Section title={`Household (${data.household?.length ?? 0})`}>
        <Flex direction="column" alignItems="stretch" gap={1}>
          {(data.household ?? []).map((m, i) => (
            <Flex key={`${m.name}-${i}`} justifyContent="space-between" alignItems="center" gap={2}
              background={i % 2 ? 'neutral100' : undefined} hasRadius padding={2}>
              <Flex gap={2} alignItems="center" style={{ minWidth: 0 }}>
                <Typography variant="omega" textColor={m.alive ? 'neutral800' : 'neutral500'} ellipsis
                  style={{ textDecoration: m.alive ? undefined : 'line-through' }}>
                  {m.name}
                </Typography>
                <Pill>{m.relation}</Pill>
              </Flex>
              <Typography variant="pi" textColor="neutral600" style={{ flexShrink: 0 }}>
                {m.race} · {m.gender} · {m.ageYears}
              </Typography>
            </Flex>
          ))}
        </Flex>
      </Section>

      <Box>
        <Typography variant="pi" textColor="neutral500">
          A passion is marked + (minor) or ++ (major); it sets how fast that skill trains and how
          slowly it decays.
        </Typography>
      </Box>
    </Flex>
  );
}
