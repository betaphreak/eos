import type { ReactNode } from 'react';
import { Box, Flex, Typography, Button, Loader } from '@strapi/design-system';
import { signInUrl } from '../lib/serverApi';

/** Centered loader for a widget's first paint. */
export function CenteredLoader() {
  return (
    <Flex justifyContent="center" padding={4}>
      <Loader small>Loading…</Loader>
    </Flex>
  );
}

/** Shown when the server returns 401/403 — the operator needs a *server* login (not the Strapi one). */
export function Gate({ status }: { status: number }) {
  const msg =
    status === 401
      ? 'Not signed in to the game server.'
      : 'Your server account is not an admin (add it to CIVSTUDIO_AUTH_ADMINS).';
  return (
    <Flex direction="column" alignItems="flex-start" gap={2} padding={2}>
      <Typography variant="omega" textColor="neutral600">
        {msg}
      </Typography>
      {status === 401 && (
        <Button variant="tertiary" onClick={() => window.open(signInUrl, '_blank', 'noopener')}>
          Sign in with Steam ↗
        </Button>
      )}
    </Flex>
  );
}

/** A compact labelled stat cell. */
export function Stat({ label, value, emphasis }: { label: string; value: ReactNode; emphasis?: boolean }) {
  return (
    <Box background="neutral100" hasRadius padding={2} style={{ minWidth: 92 }}>
      <Typography variant="pi" textColor="neutral600">
        {label}
      </Typography>
      <Box paddingTop={1}>
        <Typography variant="omega" fontWeight="bold" textColor={emphasis ? 'primary600' : 'neutral800'}>
          {value}
        </Typography>
      </Box>
    </Box>
  );
}

/** A transient status line for a widget's last action result. */
export function ActionResult({ message, tone }: { message: string | null; tone: 'success' | 'danger' }) {
  if (!message) return null;
  return (
    <Typography variant="pi" textColor={tone === 'danger' ? 'danger600' : 'success600'}>
      {message}
    </Typography>
  );
}
