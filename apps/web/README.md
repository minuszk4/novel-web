# Novel Web — Web client

Angular 22 standalone client for private reading, import, authoring, translation, chapter-credit sandbox, account privacy, and cultivation workflows. Demo-only routes remain separated from server-backed account state.

## Run the API locally

From `apps/api`, start the API with the included wrapper:

```powershell
cd apps/api
.\mvnw.cmd spring-boot:run
```

The default profile exposes `/api/v1/system/status` and `/actuator/health`. The optional PostgreSQL profile also provides server sessions, versioned policy, library/work catalogs and personal reading-state/collection routes. Start PostgreSQL and the API using the disposable/local project steps in `apps/api/README.md`; Google sign-in and product policy content still need owner-approved setup.

## Run the web demo

```powershell
cd apps/web
npm start
```

Open `http://localhost:4200`. The interface queries the API status through the Angular dev-server proxy. If the API is stopped, it clearly reports that it is unavailable. Demo library metadata, reading status and collections persist in this browser's `localStorage`; consent, reader preferences, bookmarks, reading progress and cultivation controls remain in memory and reset on reload. Demo data is local to this browser and is not an account sync feature.

Run the frontend checks from this directory:

```powershell
npm ci
npm run format:check
npm run test:ci
npm run build
npm audit --audit-level=high
```

Before committing frontend changes, run `npm run format` to apply the repository Prettier style. CI runs `format:check` so formatting drift fails the frontend job.

`test:ci` writes JUnit XML to `reports/junit.xml` and HTML/Cobertura coverage under `coverage/web/`. Coverage includes all `src/**/*.ts` files except specs. The Angular test target enforces a baseline floor of 15% statements, 18% branches, 20% functions and 15% lines; the clean-clone 41-test suite at `f6fc681` measures 18.95%, 25.35%, 26.96% and 18.54%, respectively. These floors prevent regression while the test suite grows; they are not a release-readiness target. CI retains the JUnit and coverage artifacts for 14 days.

## Browser regression checks

Install the Chromium browser used by Playwright once on a new machine:

```powershell
npx playwright install chromium
```

Run the reader browser checks from this directory:

```powershell
npm run test:e2e
```

Playwright starts the Angular dev server on `127.0.0.1:4300`. Coverage includes demo and account routes, Reader dialogs at 360, 768 and 1280 px; keyboard shortcuts, route/heading focus, modal containment/restoration, themes and font limits; sign-in/demo separation; setup consent geometry and keyboard use; search, library, cultivation and import states; and selected account Reader and Import journeys. Account routes use intercepted sessions/API responses for authorization, errors, retry, pagination, preview/edit/commit, focus recovery, responsive layout and axe checks. They do not prove real Google sign-in or live account services. Automated scans catch common DOM-level accessibility defects; screen-reader speech, complex-background contrast and participant usability remain manual work. The standard run writes JUnit output to `reports/e2e-junit.xml` and traces/screenshots to `reports/e2e-results/`; CI retains them for 14 days.

Latest hosted CI for source commit `d53229e`: frontend unit tests 420/420; E2E 329 passed with 20 PostgreSQL-live fixtures skipped; build and npm audit pass. The full run is [GitHub Actions 45543920459](https://github.com/minuszk4/novel-web/actions/runs/45543920459); PR-only dependency review is skipped on direct pushes. The opt-in PostgreSQL browser suite is run separately.

`e2e/reader-narrow-layout.spec.ts` checks the demo Reader toolbar, progress row, bookmark and next-chapter controls at 180×400 CSS px/DPR 2 (a 200%-equivalent narrow viewport, not a claim of browser zoom), and verifies the “Đọc thử tác phẩm mẫu” link is reachable above mobile navigation from the empty Home state at 360×800. Post-fix Chromium captures for those two cases are kept in `.ai/evidence/product-flow-audit-2026-11-11/after-fix/`.

The 2026-11-11 core-journey UX follow-up raises the mobile API-status retry, Reader navigation/bookmark, and Reader-settings controls to at least 44×44 CSS px, while preserving the setup consent/footer clearance of at least 8 px. The focused `auth-demo.spec.ts`, `reader-narrow-layout.spec.ts` and `setup-consent.spec.ts` run passes 18/18 after these changes. This is a historical focused UX follow-up; the current hosted CI result is listed above. A fresh screenshot set for sign-in → demo setup → sample work → Reader is under `.ai/evidence/ux-product-flow-audit-2026-11-11-after-fix/`.

The Reader's mobile header keeps a visible arrow to return to Work Detail while hiding the longer text label. The link has the accessible name “Quay lại chi tiết tác phẩm” and a minimum 40×40 px hit area; the browser regression verifies it is visible and navigates back at 360, 768 and 1280 px.

Run the current account journey from the repository root with `.\tools\e2e\run-library-postgres.ps1 -IncludeAccountDataExport`. It creates a randomly named disposable PostgreSQL project, applies 188 Liquibase changesets, starts the real API and drives Angular through its proxy in Chromium. The 12/12 no-mock run covers private import, Reader progress/bookmark, account export request/download and ZIP-content checks for the imported work. It removes only its container/network/volume, private staging and synthetic ZIPs; evidence is written to `.ai/evidence/library-live-postgres-<date>/`. The harness pre-provisions a synthetic session/policy state, so it does not exercise Google GIS or interactive policy consent.

Mobile import regressions also verify that unsupported-format feedback and the selected filename stay above the fixed action bar, the selected-file “Đổi tệp” control is usable and clears the previous rights affirmation, and Review keeps its submit action above bottom navigation. These UI checks use the metadata-only demo path; they do not replace the account-import API tests or live storage configuration.

## Reader keyboard controls

- `Alt+Shift+T` toggles the table of contents.
- `Alt+Shift+J` opens the next chapter; `Alt+Shift+K` opens the previous chapter.
- `Escape` closes the open table of contents or reader settings panel.

The shortcuts do not run while focus is in a text-entry control and are ignored when Control or Meta is also held. The visible Reader controls remain available as alternatives. The shortcuts and open drawers are transient; account progress, bookmarks and Reader preferences have separate API persistence.

Reader table-of-contents and settings drawers use modal keyboard behavior: focus enters the drawer, Tab stays inside it, and Escape or the close action returns focus to the control that opened it. Choosing a chapter moves focus to the new chapter heading.

## Routes in this slice

- `/`, `/sign-in`, `/app/setup` — landing, Google Identity Services entry and account policy setup.
- `/app/home`, `/app/library`, `/app/search`, `/app/history`, `/app/notifications` — private reading state, discovery and inbox.
- `/app/import`, `/app/import/review` — metadata-only demo import, or server-backed EPUB/TXT/HTML/Markdown import and private commit.
- `/app/works/:workId`, `/app/reader/:workId/:chapterId` — work detail, access-aware Reader, credit paywall, progress, bookmarks and notes.
- `/app/credits`, `/app/purchases`, `/app/payment/result` — credit packs, purchase history and payment-result recovery. MoMo is TEST-only and feature-gated.
- `/app/authoring/**` — private manuscripts, chapters and collaboration; collaborators have VIEW/EDIT and owners publish.
- `/app/translation/**`, `/app/review/translations/**` — fake-local jobs, candidate review/editing and gated publication workflows.
- `/app/glossaries/**`, `/app/workspaces`, `/app/cultivation` — glossary tools, shared-library membership and opted-in account XP/ranks.
- `/app/review/**`, `/app/admin/reviewers`, `/app/settings` — assigned publication review, reviewer administration, preferences, privacy choices and account export.

## Integration boundary

Google Identity Services is wired into sign-in: the client requests a server bootstrap, loads GIS and renders Google's provider button. A real Google sign-in still requires a configured client, allowed origins/callback URI, and deployment secrets. Local/live browser fixtures use synthetic authenticated sessions and do not verify a real Google account.

With a valid server session, the client reads `/auth/me`, current policy documents and account library/work/reading-state APIs. Account import supports EPUB/TXT up to the configured limit (default 50 MiB), HTML up to 4 MiB and Markdown up to 16 MiB. Markdown chapters come from repeated top-level headings; visible link labels and image alt text stay while destinations and raw HTML are discarded. The flow records rights confirmation, sends bytes to a one-time private staging target, asks the API to scan/parse, loads an editable paginated preview and commits with version/idempotency checks. The default staging path is under `java.io.tmpdir`; production still needs private durable storage plus cleanup and backup operations.

The account Reader loads a metadata-only manifest and fetches normalized source segments only for readable chapters. Progress, bookmarks, preferences, reading history and annotations sync through account APIs. Paid-credit chapter unlock and entitlement are implemented for the gated sandbox flow; locked-chapter excerpt preview is not implemented. MoMo requests in tests use synthetic/local responses, not an external merchant. Production checkout, commercial prices/refunds, licensed-content approval and merchant UAT remain unverified.

Fake-local translation preview/jobs and controlled candidate review/publication are implemented behind server gates; chapter text is not sent to an external translation provider. Account cultivation uses opted-in server-backed activity and rank state. The `620 XP` / `34 XP` values on the demo Reader remain illustrative and are not account progress.

Account export generates a ZIP that includes content owned by the account. The live no-mock harness verifies an imported work title, chapter text, saved Reader progress and bookmark in the downloaded archive. Account deletion, legal hold and financial/audit retention are not implemented pending policy decisions.

The demo import path is metadata-only: it reads filename, declared MIME type and size but does not read or upload file bytes. Demo library metadata, reading status and collections use this browser's `localStorage`; demo consent, Reader preferences, bookmarks, progress and cultivation controls stay in memory. Optional privacy choices are stored but do not activate analytics or external content sharing. The full implementation boundary and remaining work are recorded in the local `.ai` planning documents, which are intentionally Git-ignored.
