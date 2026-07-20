import { Flex, Table, Thead, Tbody, Tr, Th, Td, Typography } from '@strapi/design-system';
import { serverFetch, type ColonyDetail } from '../../lib/serverApi';
import { useServerPoll } from '../../lib/useServerPoll';
import { CenteredLoader, Gate } from '../opsShared';
import { Detail, Empty, LoadError, Section, SkillBar } from '../sessionBits';

/**
 * A colony's composition — the same payload as the web client's colony rail
 * (GET /api/sessions/{sid}/colony): vitals, a colony-average skill profile, and the household
 * roster with the ruler first.
 *
 * `colony` names a seat other than the POV one (the ?colony= param added in plan §C1); without it
 * this is the run's first colony, which is all a single-colony run has.
 */
export default function ColonyPanel({ sessionId, colony }: { sessionId: string; colony?: string }) {
  const q = colony ? `?colony=${encodeURIComponent(colony)}` : '';
  const { data, loading, gate, error } = useServerPoll<ColonyDetail>(() =>
    serverFetch('GET', `/api/sessions/${sessionId}/colony${q}`),
  );

  if (gate) return <Gate status={gate} />;
  if (loading && !data) return <CenteredLoader />;
  // the failed read must not fall through to the empty state below — see LoadError
  if (error && !data) return <LoadError message={error} />;
  if (!data?.name) return <Empty>This run has no colony.</Empty>;

  const skills = [...(data.skills ?? [])].sort((a, b) => b.avg - a.avg);

  return (
    <Flex direction="column" alignItems="stretch" gap={4}>
      <Section title={data.name}>
        <Flex gap={4} wrap="wrap">
          {data.tier && <Detail label="Tier" value={data.tier} />}
          {data.province && <Detail label="Province" value={data.province} />}
          {data.rulerName && <Detail label="Ruler" value={data.rulerName} />}
          <Detail label="Population" value={data.population.toLocaleString()} />
          <Detail label="Nobles" value={data.nobles} />
          <Detail label="Pool" value={data.poolSize.toLocaleString()} />
        </Flex>
      </Section>

      <Section title="Skill profile">
        {skills.length === 0 ? (
          <Empty>No skills to average — the colony has no living members.</Empty>
        ) : (
          <Flex direction="column" alignItems="stretch" gap={2}>
            {skills.map((s) => (
              <SkillBar key={s.skill} label={s.skill} value={s.avg} />
            ))}
          </Flex>
        )}
      </Section>

      <Section title={`Households (${data.members?.length ?? 0})`}>
        {!data.members?.length ? (
          <Empty>No households.</Empty>
        ) : (
          <Table colCount={5} rowCount={data.members.length}>
            <Thead>
              <Tr>
                <Th><Typography variant="sigma">Name</Typography></Th>
                <Th><Typography variant="sigma">Role</Typography></Th>
                <Th><Typography variant="sigma">Race</Typography></Th>
                <Th><Typography variant="sigma">Age</Typography></Th>
                <Th><Typography variant="sigma">Top skill</Typography></Th>
              </Tr>
            </Thead>
            <Tbody>
              {data.members.map((m, i) => (
                <Tr key={`${m.name}-${i}`}>
                  <Td>
                    <Typography textColor="neutral800"
                      fontWeight={m.ruler ? 'bold' : undefined}>
                      {m.name}
                    </Typography>
                  </Td>
                  <Td>
                    <Typography textColor={m.ruler ? 'primary600' : m.noble ? 'secondary600' : 'neutral700'}>
                      {m.role}
                    </Typography>
                  </Td>
                  <Td><Typography textColor="neutral700">{m.race}</Typography></Td>
                  <Td><Typography textColor="neutral700">{m.age}</Typography></Td>
                  <Td>
                    <Typography textColor="neutral700">
                      {m.topSkill ? `${m.topSkill} ${m.topSkillLevel}` : '—'}
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
