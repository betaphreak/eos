import { Box, Flex, LinkButton, Typography } from '@strapi/design-system';
import { ExternalLink } from '@strapi/icons';
import { worldMapBase, worldMapUrl } from '../lib/worldmap';
import WorldMapFrame from './WorldMapFrame';

/**
 * Homepage widget: the world map, live, in the admin.
 *
 * Deliberately short — a homepage widget is a glance, not a workspace — with a link out to the full
 * World map page (and to the viewer itself) for anything more. The iframe needs the `frame-src` CSP
 * entry in config/middlewares.ts; without it the panel renders empty and the browser console carries
 * the refusal.
 */
export default function WorldMapWidget() {
  return (
    <Flex direction="column" alignItems="stretch" gap={2} style={{ height: '100%' }}>
      <WorldMapFrame height={260} title="CivStudio world map (widget)" />
      <Flex justifyContent="space-between" alignItems="center" gap={2}>
        <Typography variant="pi" textColor="neutral600">
          {worldMapBase.replace(/^https?:\/\//, '')}
        </Typography>
        <Box>
          <LinkButton href={worldMapUrl()} target="_blank" rel="noopener noreferrer"
            variant="tertiary" size="S" endIcon={<ExternalLink />}>
            Open full map
          </LinkButton>
        </Box>
      </Flex>
    </Flex>
  );
}
