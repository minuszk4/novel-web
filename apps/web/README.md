# Novel Web — Frontend demo

Angular 22 standalone client for exploring the private reading flow and the cultivation-rank concept.

## Run the API locally

From `apps/api`, start the API with the included wrapper:

```powershell
cd apps/api
.\mvnw.cmd spring-boot:run
```

The starter exposes `/api/v1/system/status` and `/actuator/health`; integration flags remain disabled. It has no database-backed modules yet.

## Run the web demo

```powershell
cd apps/web
npm start
```

Open `http://localhost:4200`. The interface queries the API status through the Angular dev-server proxy. If the API is stopped, it clearly reports that it is unavailable. Demo state is held in Angular signals in memory and resets on reload.

Run the frontend checks from this directory:

```powershell
npm ci
npm run test:ci
npm run build
npm audit --audit-level=high
```

`test:ci` writes JUnit XML to `reports/junit.xml` and HTML/Cobertura coverage under `coverage/web/`. Coverage includes all `src/**/*.ts` files except specs, so the current line-coverage result is a low baseline for a small foundation suite, not a release threshold. CI retains the JUnit and coverage artifacts for 14 days.

## Browser regression checks

Install the Chromium browser used by Playwright once on a new machine:

```powershell
npx playwright install chromium
```

Run the reader browser checks from this directory:

```powershell
npm run test:e2e
```

Playwright starts the Angular dev server on `127.0.0.1:4300`. The suite covers reader keyboard shortcuts, chapter route and heading focus, modal focus containment/restoration, backdrop close, a 360px layout check, and axe-core WCAG 2.2 A/AA plus best-practice scans for all 12 demo routes and both Reader dialogs at 360px, 768px and 1280px. It also checks supported/unsupported import-file states, rights confirmation, metadata preview, long filenames and horizontal overflow at mobile, tablet and desktop sizes; onboarding consent gating is tested at all three viewport sizes and at zero/partial/complete consent states. The sign-in flow confirms Google is explicitly unavailable in the demo, exposes no fake provider button, and sends the demo CTA to setup without creating an account. Private search checks accessible field naming, matching private metadata, a no-result message that repeats the query, an explicit clear action, and focus return at all three viewports. Reader Sepia/Dark themes and minimum/maximum font sizes are also checked for axe violations at all three viewport sizes, with a no-overflow check at both font limits. The automated setup scan waits for the enabled CTA's background transition to settle before measuring contrast. Automated scans catch common DOM-level accessibility defects; keyboard, screen-reader, contrast-on-complex-background and usability review remain manual work. It writes JUnit output to `reports/e2e-junit.xml` and failure traces/screenshots to `reports/e2e-results/`; CI installs Chromium with system dependencies and retains these reports for 14 days.

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
- `/app/import`, `/app/import/review` — local file metadata and private-import confirmation.
- `/works/demo-story`, `/read/demo-story/:chapterId` — original sample story and reader controls.
- `/app/cultivation` — opt-in/pause interaction, proposed rank ladder, sample XP card.
- `/app/settings` — reader preferences and optional privacy toggles.

## Integration boundary

Google Identity Services, a backend session, policy documents, file parsing/storage, content synchronization and MoMo Test checkout are not connected. File selection reads only filename, declared MIME type and size; it does not read or upload bytes. The story text and the `620 XP` / `34 XP` cultivation example are original illustrative demo data, not imported content or a user's progression. Settings, bookmarks and reading progress remain in memory only.

The full implementation boundary and remaining work are recorded in the local `.ai` planning documents. Those files are intentionally ignored by Git per the workspace setup.
