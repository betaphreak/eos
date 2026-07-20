import { Routes, Route } from 'react-router-dom';
import SessionListPage from './SessionListPage';
import SessionDetailPage from './SessionDetailPage';

/**
 * The Sessions admin page — the first custom admin PAGE in this project (everything before it was a
 * homepage widget or a content-manager view).
 *
 * Strapi registers a menu link's route as `<to>/*` (see Router#addMenuLink), so this component owns
 * everything under it and routes internally: the list at the root, one run at `:id`. Registered from
 * `app.tsx`'s `register` — NOT `bootstrap`, whose argument is a restricted Pick with no
 * `addMenuLink`, exactly as with `widgets`.
 */
export default function Sessions() {
  return (
    <Routes>
      <Route index element={<SessionListPage />} />
      <Route path=":id" element={<SessionDetailPage />} />
    </Routes>
  );
}
