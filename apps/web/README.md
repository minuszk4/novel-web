# Novel Web — Frontend demo

Angular 22 standalone client for exploring the private reading flow and the cultivation-rank concept.

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

Playwright starts the Angular dev server on `127.0.0.1:4300`. Coverage includes all 12 demo routes and both Reader dialogs at 360, 768 and 1280 px; keyboard shortcuts, route/heading focus, modal containment/restoration, themes and font limits; sign-in/demo separation; setup consent geometry and keyboard use; search, library, cultivation and import states; and selected account Reader and Import journeys. Account routes use intercepted sessions/API responses for authorization, errors, retry, pagination, preview/edit/commit, focus recovery, responsive layout and axe checks. They do not prove real Google sign-in or live account services. Automated scans catch common DOM-level accessibility defects; screen-reader speech, complex-background contrast and participant usability remain manual work. The standard run writes JUnit output to `reports/e2e-junit.xml` and traces/screenshots to `reports/e2e-results/`; CI retains them for 14 days.

Latest full local run at HEAD `eb77ea8`: `npm run test:e2e -- --workers=4 --reporter=dot` — **130 passed, 1 fixture-gated PostgreSQL test skipped** (131 total; 2.8 minutes). The API was not running, so expected proxy `ECONNREFUSED` messages occurred for fallback states; this run does not claim a live API/PostgreSQL browser journey. Run that separately with `tools/e2e/run-library-postgres.ps1`.

`e2e/reader-narrow-layout.spec.ts` checks the demo Reader toolbar, progress row, bookmark and next-chapter controls at 180×400 CSS px/DPR 2 (a 200%-equivalent narrow viewport, not a claim of browser zoom), and verifies the “Đọc thử tác phẩm mẫu” link is reachable above mobile navigation from the empty Home state at 360×800. Post-fix Chromium captures for those two cases are kept in `.ai/evidence/product-flow-audit-2026-11-11/after-fix/`.

The 2026-11-11 core-journey UX follow-up raises the mobile API-status retry, Reader navigation/bookmark, and Reader-settings controls to at least 44×44 CSS px, while preserving the setup consent/footer clearance of at least 8 px. The focused `auth-demo.spec.ts`, `reader-narrow-layout.spec.ts` and `setup-consent.spec.ts` run passes 18/18 after these changes. The full 131-case run at HEAD `eb77ea8` is recorded above. A fresh screenshot set for sign-in → demo setup → sample work → Reader is under `.ai/evidence/ux-product-flow-audit-2026-11-11-after-fix/`.

The Reader's mobile header keeps a visible arrow to return to Work Detail while hiding the longer text label. The link has the accessible name “Quay lại chi tiết tác phẩm” and a minimum 40×40 px hit area; the browser regression verifies it is visible and navigates back at 360, 768 and 1280 px.

Run the live account-library journey from the repository root with `.\tools\e2e\run-library-postgres.ps1`. It starts a randomly named disposable PostgreSQL 17 project and the real API, creates a test-only session/policy/library fixture, then drives the Angular app through its dev-server proxy in Chromium. The test checks account identity in the shell, current policy/document loading, libraries, works and per-work reading state with no API route mocks. The script removes only its own container/network/volume and saves logs plus a screenshot under `.ai/evidence/library-live-postgres-<date>/`. It does not exercise Google GIS, real policy approval or production data.

Mobile import regressions also verify that unsupported-format feedback and the selected filename stay above the fixed action bar, the selected-file “Đổi tệp” control is usable and clears the previous rights affirmation, and Review keeps its submit action above bottom navigation. These UI checks use the metadata-only demo path; they do not replace the account-import API tests or live storage configuration.

## Reader keyboard controls

- `Alt+Shift+T` toggles the table of contents.
- `Alt+Shift+J` opens the next chapter; `Alt+Shift+K` opens the previous chapter.
- `Escape` closes the open table of contents or reader settings panel.

The shortcuts do not run while focus is in a text-entry control and are ignored when Control or Meta is also held. The visible Reader controls remain available as alternatives. These interactions are demo-only and do not persist across reloads.

Reader table-of-contents and settings drawers use modal keyboard behavior: focus enters the drawer, Tab stays inside it, and Escape or the close action returns focus to the control that opened it. Choosing a chapter moves focus to the new chapter heading.

## Routes in this slice

- `/` — product introduction.
- `/sign-in` — Google provider status and demo entry.
- `/app/setup` — separate Terms and Privacy demo acknowledgments.
- `/app/home`, `/app/library`, `/app/search` — private-library shell and metadata.
- `/app/import`, `/app/import/review` — metadata-only demo import, or account EPUB/TXT/HTML/Markdown upload, preview and private-import confirmation when a server session is active.
- `/works/demo-story`, `/read/demo-story/:chapterId` — original sample story and reader controls.
- `/app/reader/:workId`, `/app/reader/:workId/:chapterId` — account Reader, backed by private manifest and readable source-chapter endpoints.
- `/app/cultivation` — opt-in/pause interaction, proposed rank ladder, sample XP card.
- `/app/settings` — reader preferences and optional privacy toggles.

## Integration boundary

Google Identity Services is wired into the sign-in page: it requests the server bootstrap, loads Google's GIS client and renders the provider button. A real sign-in still depends on a configured Google client, allowed origins/callback URI and deployment secrets; the browser journey uses a synthetic test session and has not verified a real Google account.

With a valid server session, the client reads `/auth/me`, current policy documents and account library/work/reading-state APIs; the shell displays the server account identity. The live integration harness exercises those APIs with test-only data. Account import accepts EPUB/TXT up to the configured limit (default 50 MiB), HTML up to 4 MiB and Markdown up to 16 MiB. Markdown chapters come from repeated top-level headings; visible link labels and image alt text stay, while link destinations and raw HTML are discarded. The flow records rights confirmation, uploads the bytes to a one-time target in private server staging, asks the API to scan and parse the book, loads an editable paginated preview, and commits the private work with optimistic-version and idempotency checks. The API's default staging directory is under the machine's temporary directory (`java.io.tmpdir/novel-web-import-staging`); production still needs an explicitly provisioned, private and durable storage location and operational cleanup/backup policy. The test journey does not establish a production storage deployment.

The account Reader loads a metadata-only paginated manifest and only requests normalized source segments for a chapter marked readable. It skips unavailable chapters during navigation, provides loading/error/empty states and temporary session-only font/theme settings. Session recovery retains the Reader return route through sign-in or policy setup. Paid entitlement, preview ranges, translation, synced progress/bookmarks/preferences, and live account Reader → API → PostgreSQL browser verification are not implemented.

The demo import path is deliberately metadata-only: it reads the selected filename, declared MIME type and size but does not read or upload bytes. The story text and `620 XP` / `34 XP` cultivation example are original illustrative demo data, not imported content or a user's progression. Multi-device sync for demo state and MoMo Test checkout are not connected. Demo library metadata, reading status and collections use this browser's `localStorage`; consent, reader preferences, bookmarks, reading progress and cultivation controls remain in memory only. None of this demo state is server-backed.

The full implementation boundary and remaining work are recorded in the local `.ai` planning documents. Those files are intentionally ignored by Git per the workspace setup.
