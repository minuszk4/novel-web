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
