import { LinkButton } from '@strapi/design-system';
import { Earth } from '@strapi/icons';
import { unstable_useContentManagerContext as useContentManagerContext } from '@strapi/strapi/admin';
import { provinceMapUrl } from '../lib/worldmap';

const PROVINCE_UID = 'api::province.province';

/**
 * "Open in world map" on the province edit view — the CMS→map half of the two-way link
 * (docs/studio-control-plane-plan.md §D1).
 *
 * Injected into the content-manager's `editView` / `right-links` zone, which is shared by EVERY
 * content type, so it renders nothing unless the entry being edited is a province. It reads the
 * live FORM values rather than the saved document, so an unsaved realm change links where the
 * editor is looking, not where the row last was.
 *
 * Deliberately a plain external link: the viewer is another origin, and linking out needs no CSP
 * change, no iframe and no postMessage bridge. Embedding is §D4, only if this proves insufficient.
 */
export default function OpenInWorldMap() {
  // `form` is typed `unknown` on the context (it is the useForm return); values is what we need.
  const ctx = useContentManagerContext() as unknown as {
    model?: string;
    isCreatingEntry?: boolean;
    form?: { values?: Record<string, unknown> };
  };

  if (ctx?.model !== PROVINCE_UID || ctx?.isCreatingEntry) return null;

  const values = ctx.form?.values ?? {};
  const provinceId = values.provinceId as number | string | undefined;
  // a province with no id yet is not on the map — offer nothing rather than a link to nowhere
  if (provinceId == null || provinceId === '') return null;
  const realm = (values.realm as string | undefined) ?? null;

  return (
    <LinkButton
      href={provinceMapUrl(provinceId, realm)}
      target="_blank"
      rel="noopener noreferrer"
      variant="tertiary"
      startIcon={<Earth />}
      size="S"
      width="100%"
    >
      Open in world map
    </LinkButton>
  );
}
