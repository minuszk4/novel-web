import { mkdir, writeFile } from 'node:fs/promises';
import { arch, cpus, platform, release, totalmem } from 'node:os';
import { resolve } from 'node:path';
import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route, TestInfo } from '@playwright/test';

const libraryId = '33333333-3333-4333-8333-333333333333';
const libraryPageSize = 25;
const libraryWorkCount = 200;
const catalogPageSize = 20;
const catalogWorkCount = 1_000;
const initialLoadSamples = 10;
const searchSamples = 10;
type LongTaskSample = { startTime: number; duration: number };
type DomActionSample = { kind: string; durationMs: number };
type CatalogTimingWindow = Window & {
  __catalogInitialDomDurations?: number[];
  __catalogPendingDomAction?: { kind: string; buttonText: string; expectedCount: number };
  __catalogActiveDomAction?: {
    kind: string;
    expectedCount: number;
    startTime: number;
    completionScheduled: boolean;
  };
  __catalogDomActionDurations?: DomActionSample[];
  __catalogLongTasks?: LongTaskSample[];
  __catalogSearchDomDurations?: Array<{ query: string; durationMs: number }>;
  __catalogSearchSequence?: number;
  __catalogExpectedFirstTitle?: string;
};

const account = {
  user: {
    id: 'user-1',
    displayName: 'Độc giả',
    contactEmail: 'reader@example.test',
    avatarUrl: null,
    status: 'ACTIVE',
  },
  identityProviders: ['google'],
  policy: {
    termsAcceptedVersion: 'terms-1',
    privacyNoticeAcknowledgedVersion: 'privacy-1',
    requiredAcceptanceComplete: true,
  },
  roles: [],
  features: {
    privateLibraryEnabled: true,
    importEnabled: true,
    readerEnabled: true,
    commerceEnabled: false,
    cultivationEnabled: false,
  },
};

const publicCatalogEnabledStatus = {
  apiVersion: '9.4.0',
  environment: 'test',
  integrations: {
    googleOidcEnabled: false,
    vnpayTestEnabled: false,
    vnpayEnvironment: 'TEST',
  },
  features: {
    importProcessingEnabled: false,
    cultivationEnabled: false,
    commerceSandboxEnabled: false,
    publicCatalogEnabled: true,
    translationFakePreviewEnabled: false,
  },
};

function percentile95(samples: number[]): number {
  const ordered = [...samples].sort((left, right) => left - right);
  return ordered[Math.max(0, Math.ceil(ordered.length * 0.95) - 1)] ?? 0;
}

function localDate(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
}

function uuid(index: number, prefix: string): string {
  return `${prefix}-0000-4000-8000-${String(index).padStart(12, '0')}`;
}

async function json(
  route: Route,
  body: unknown,
  status = 200,
  headers: Record<string, string> = {},
): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    headers,
    body: JSON.stringify(body),
  });
}

async function installBrowserDomTiming(
  page: Page,
  mode: 'library' | 'catalog',
  initialItemCount: number,
): Promise<void> {
  await page.addInitScript(
    ({ pageMode, itemCount }) => {
      const target = window as CatalogTimingWindow;
      target.__catalogInitialDomDurations = [];
      target.__catalogDomActionDurations = [];
      let initialCompletionScheduled = false;

      const isReady = (expectedCount: number): boolean => {
        if (pageMode === 'library') {
          const rows = Array.from(document.querySelectorAll('.library-work-row'));
          return (
            rows.length === expectedCount &&
            rows.every(
              (row) =>
                row.querySelector('.private-tag')?.textContent?.trim() === 'RIÊNG TƯ · TÀI KHOẢN',
            )
          );
        }
        const cards = document.querySelectorAll('.public-catalog-card');
        const announcement =
          document.querySelector('.public-catalog-announcement')?.textContent?.trim() ?? '';
        return (
          cards.length === expectedCount && announcement === `${expectedCount} truyện đã được tải.`
        );
      };

      const scheduleInitialMeasurement = (): void => {
        if (initialCompletionScheduled || (target.__catalogInitialDomDurations?.length ?? 0) > 0) {
          return;
        }
        initialCompletionScheduled = true;
        requestAnimationFrame(() => {
          initialCompletionScheduled = false;
          if (!isReady(itemCount)) return;
          target.__catalogInitialDomDurations?.push(performance.now());
        });
      };

      const scheduleActionMeasurement = (): void => {
        const action = target.__catalogActiveDomAction;
        if (!action || action.completionScheduled) return;
        action.completionScheduled = true;
        requestAnimationFrame(() => {
          if (target.__catalogActiveDomAction !== action) return;
          action.completionScheduled = false;
          if (!isReady(action.expectedCount)) return;
          target.__catalogDomActionDurations?.push({
            kind: action.kind,
            durationMs: performance.now() - action.startTime,
          });
          target.__catalogActiveDomAction = undefined;
        });
      };

      const observer = new MutationObserver(() => {
        scheduleInitialMeasurement();
        scheduleActionMeasurement();
      });
      observer.observe(document, {
        childList: true,
        characterData: true,
        subtree: true,
      });
      document.addEventListener(
        'click',
        (event) => {
          const pending = target.__catalogPendingDomAction;
          const eventTarget = event.target;
          if (!pending || !(eventTarget instanceof Element)) return;
          const button = eventTarget.closest('button');
          const buttonText = button?.textContent?.trim().replace(/\s+/g, ' ');
          if (buttonText !== pending.buttonText) return;
          target.__catalogPendingDomAction = undefined;
          target.__catalogActiveDomAction = {
            kind: pending.kind,
            expectedCount: pending.expectedCount,
            startTime: performance.now(),
            completionScheduled: false,
          };
          scheduleActionMeasurement();
        },
        true,
      );
      scheduleInitialMeasurement();
    },
    { pageMode: mode, itemCount: initialItemCount },
  );
}

async function writeProductionEvidence(
  testInfo: TestInfo,
  files: Array<{ name: string; markdown: string; json: unknown }>,
): Promise<void> {
  const baseURL = testInfo.project.use.baseURL;
  if (typeof baseURL !== 'string' || new URL(baseURL).port !== '4310') return;

  const evidenceDirectory = resolve(
    process.cwd(),
    '../../.ai/evidence',
    `library-search-performance-${localDate()}`,
  );
  await mkdir(evidenceDirectory, { recursive: true });
  await Promise.all(
    files.flatMap(({ name, markdown, json: measurements }) => [
      writeFile(resolve(evidenceDirectory, `${name}.md`), markdown),
      writeFile(
        resolve(evidenceDirectory, `${name}.json`),
        `${JSON.stringify(measurements, null, 2)}\n`,
      ),
    ]),
  );
}

async function installAccountLibraryRoutes(page: Page): Promise<{ stateReads: () => number }> {
  let stateReads = 0;
  await installBrowserDomTiming(page, 'library', libraryPageSize);
  await page.addInitScript(() => localStorage.removeItem('novel.demo.library.v1'));

  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '9.4.0',
      environment: 'test',
      integrations: {
        googleOidcEnabled: false,
        vnpayTestEnabled: false,
        vnpayEnvironment: 'disabled',
      },
      features: {
        importProcessingEnabled: false,
        cultivationEnabled: false,
        commerceSandboxEnabled: false,
        translationFakePreviewEnabled: false,
      },
    }),
  );
  await page.route('**/api/v1/auth/me', (route) => json(route, account));
  await page.route('**/api/v1/auth/policies/current', (route) =>
    json(route, {
      terms: {
        version: 'terms-1',
        effectiveAt: '2026-11-01T00:00:00Z',
        documentUrl: '/api/v1/auth/policies/terms/terms-1',
      },
      privacyNotice: {
        version: 'privacy-1',
        effectiveAt: '2026-11-01T00:00:00Z',
        documentUrl: '/api/v1/auth/policies/privacy-notice/privacy-1',
      },
    }),
  );
  await page.route('**/api/v1/auth/policies/terms/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Terms' }),
  );
  await page.route('**/api/v1/auth/policies/privacy-notice/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Privacy' }),
  );
  await page.route('**/api/v1/auth/csrf', (route) =>
    json(route, { token: 'session-library-csrf' }),
  );
  await page.route('**/api/v1/me/library/collections', (route) => json(route, []));
  await page.route('**/api/v1/libraries**', (route) =>
    json(route, {
      items: [
        {
          id: libraryId,
          name: 'Thư viện chính',
          visibility: 'private',
          isDefault: true,
          createdAt: '2026-11-10T00:00:00Z',
        },
      ],
      nextCursor: null,
    }),
  );
  await page.route('**/api/v1/works**', (route) => {
    const requestUrl = new URL(route.request().url());
    const cursor = requestUrl.searchParams.get('cursor');
    const pageNumber = cursor ? Number(cursor.slice('library-page-'.length)) : 0;
    const start = pageNumber * libraryPageSize;
    const items = Array.from(
      { length: Math.min(libraryPageSize, libraryWorkCount - start) },
      (_, offset) => {
        const index = start + offset;
        return {
          id: uuid(index + 1, '99999999'),
          libraryId,
          title: `Tác phẩm ${String(index + 1).padStart(4, '0')}`,
          author: `Tác giả ${String(index + 1).padStart(4, '0')}`,
          language: 'vi',
          visibility: 'private',
          chapterCount: 3 + (index % 18),
          createdAt: '2026-11-10T00:00:00Z',
        };
      },
    );
    const hasMore = start + items.length < libraryWorkCount;
    return json(route, {
      items,
      nextCursor: hasMore ? `library-page-${pageNumber + 1}` : null,
    });
  });
  await page.route('**/api/v1/me/works/*/library-state', (route) => {
    stateReads += 1;
    const workId = new URL(route.request().url()).pathname.split('/')[5] ?? '';
    const index = Number(workId.slice(-12));
    const version = 0;
    return json(
      route,
      {
        workId,
        readingStatus: index % 2 === 0 ? 'completed' : 'want-to-read',
        collectionIds: [],
        version,
        updatedAt: null,
      },
      200,
      { etag: `"v${version}"` },
    );
  });

  return { stateReads: () => stateReads };
}

test('account Library renders and filters a 200-work synthetic collection', async ({
  page,
  browserName,
}, testInfo) => {
  const reads = await installAccountLibraryRoutes(page);
  const initialLoadSamplesMs: number[] = [];
  const initialLoadDriverWallSamplesMs: number[] = [];
  await page.setViewportSize({ width: 1280, height: 720 });
  const session = await page.context().newCDPSession(page);
  await session.send('Performance.enable');
  const baselineMetrics = await session.send('Performance.getMetrics');

  for (let sample = 0; sample < initialLoadSamples; sample += 1) {
    const driverStartedAt = performance.now();
    if (sample === 0) await page.goto('/app/library');
    else await page.reload();
    await expect(page.locator('.library-work-row')).toHaveCount(libraryPageSize);
    await expect.poll(reads.stateReads).toBe((sample + 1) * libraryPageSize);
    await expect
      .poll(() =>
        page.evaluate(
          () => (window as CatalogTimingWindow).__catalogInitialDomDurations?.length ?? 0,
        ),
      )
      .toBe(1);
    const initialDomDurationMs = await page.evaluate(
      () => (window as CatalogTimingWindow).__catalogInitialDomDurations?.at(-1) ?? 0,
    );
    expect(initialDomDurationMs).toBeGreaterThan(0);
    initialLoadSamplesMs.push(initialDomDurationMs);
    initialLoadDriverWallSamplesMs.push(performance.now() - driverStartedAt);
  }

  const pageAppendSamplesMs: number[] = [];
  const pageAppendDriverWallSamplesMs: number[] = [];
  for (let pageNumber = 1; pageNumber < libraryWorkCount / libraryPageSize; pageNumber += 1) {
    const expectedWorkCount = (pageNumber + 1) * libraryPageSize;
    await page.evaluate((expectedCount) => {
      (window as CatalogTimingWindow).__catalogPendingDomAction = {
        kind: 'library-append',
        buttonText: 'Tải thêm tác phẩm',
        expectedCount,
      };
    }, expectedWorkCount);
    const driverStartedAt = performance.now();
    await page.getByRole('button', { name: 'Tải thêm tác phẩm' }).click();
    await expect(page.locator('.library-work-row')).toHaveCount(expectedWorkCount);
    await expect
      .poll(reads.stateReads)
      .toBe(initialLoadSamples * libraryPageSize + pageNumber * libraryPageSize);
    await expect
      .poll(() =>
        page.evaluate(
          () => (window as CatalogTimingWindow).__catalogDomActionDurations?.length ?? 0,
        ),
      )
      .toBe(pageNumber);
    const action = await page.evaluate(() =>
      (window as CatalogTimingWindow).__catalogDomActionDurations?.at(-1),
    );
    expect(action?.kind).toBe('library-append');
    const actionDurationMs = action?.durationMs ?? 0;
    expect(actionDurationMs).toBeGreaterThan(0);
    pageAppendSamplesMs.push(actionDurationMs);
    pageAppendDriverWallSamplesMs.push(performance.now() - driverStartedAt);
  }

  await session.send('HeapProfiler.enable');
  await session.send('HeapProfiler.collectGarbage');
  const metrics = await session.send('Performance.getMetrics');
  const metric = (name: string): number =>
    metrics.metrics.find((entry) => entry.name === name)?.value ?? 0;
  const baselineMetric = (name: string): number =>
    baselineMetrics.metrics.find((entry) => entry.name === name)?.value ?? 0;
  const baseline = await page.evaluate(() => ({
    userAgent: navigator.userAgent,
    platform: navigator.platform,
    viewport: { width: innerWidth, height: innerHeight },
    deviceScaleFactor: devicePixelRatio,
    hardwareConcurrency: navigator.hardwareConcurrency,
    deviceMemoryGiB: 'deviceMemory' in navigator ? Number(navigator.deviceMemory) : null,
  }));
  const heapDeltaMiB =
    (metric('JSHeapUsedSize') - baselineMetric('JSHeapUsedSize')) / (1024 * 1024);
  const domNodeCount = metric('Nodes');

  await page.evaluate(() => {
    (window as CatalogTimingWindow).__catalogPendingDomAction = {
      kind: 'library-filter',
      buttonText: 'Hoàn thành 100',
      expectedCount: 100,
    };
  });
  const filterDriverStartedAt = performance.now();
  await page.getByRole('button', { name: 'Hoàn thành 100' }).click();
  await expect(page.locator('.library-work-row')).toHaveCount(100);
  await expect
    .poll(() =>
      page.evaluate(() => (window as CatalogTimingWindow).__catalogDomActionDurations?.length ?? 0),
    )
    .toBe(libraryWorkCount / libraryPageSize);
  const filterAction = await page.evaluate(() =>
    (window as CatalogTimingWindow).__catalogDomActionDurations?.at(-1),
  );
  expect(filterAction?.kind).toBe('library-filter');
  const filterMs = filterAction?.durationMs ?? 0;
  expect(filterMs).toBeGreaterThan(0);
  const filterDriverWallMs = performance.now() - filterDriverStartedAt;
  await page.setViewportSize({ width: 360, height: 800 });
  const mobileDimensions = await page.evaluate(() => ({
    documentWidth: document.documentElement.scrollWidth,
    viewportWidth: document.documentElement.clientWidth,
  }));
  expect(mobileDimensions.documentWidth).toBeLessThanOrEqual(mobileDimensions.viewportWidth);

  const result = {
    generatedAt: new Date().toISOString(),
    environment: {
      ...baseline,
      browserProject: `${testInfo.project.name || 'default'} (${browserName})`,
      host: {
        operatingSystem: `${platform()} ${release()}`,
        architecture: arch(),
        physicalMemoryGiB: Number((totalmem() / 1024 ** 3).toFixed(1)),
        cpuModel: cpus()[0]?.model ?? 'unknown',
      },
      playwright: '1.65.0 (pinned @playwright/test)',
    },
    dataset: {
      workCount: libraryWorkCount,
      pageSize: libraryPageSize,
      initialPages: initialLoadSamples,
      appendPageCount: libraryWorkCount / libraryPageSize - 1,
      workStateReadsPerFullLibrary: libraryWorkCount,
      workStateReadsObserved: reads.stateReads(),
    },
    measurements: {
      initialLoadSamplesMs,
      firstNavigationDomReadyMs: initialLoadSamplesMs[0] ?? 0,
      warmNavigationDomReadyP95Ms: percentile95(initialLoadSamplesMs.slice(1)),
      firstNavigationDriverWallMs: initialLoadDriverWallSamplesMs[0] ?? 0,
      warmNavigationDriverWallP95Ms: percentile95(initialLoadDriverWallSamplesMs.slice(1)),
      initialLoadDriverWallSamplesMs,
      appendPageP95Ms: percentile95(pageAppendSamplesMs),
      appendPageSamplesMs: pageAppendSamplesMs,
      appendPageDriverWallP95Ms: percentile95(pageAppendDriverWallSamplesMs),
      appendPageDriverWallSamplesMs: pageAppendDriverWallSamplesMs,
      statusFilterDomUpdateMs: filterMs,
      statusFilterDriverWallMs: filterDriverWallMs,
      chromiumJsHeapDeltaMiB: heapDeltaMiB,
      chromiumDomNodeCount: domNodeCount,
      mobileDocumentWidth: mobileDimensions.documentWidth,
      mobileViewportWidth: mobileDimensions.viewportWidth,
    },
    scope: [
      'Production Angular build served locally from the loopback static server.',
      'Account, library, work-state and policy endpoints are deterministic Playwright route mocks; PostgreSQL/API latency is excluded.',
      'Browser render/action durations are captured inside Chromium through the next animation frame after the target DOM state; Playwright driver wall time is recorded separately. This is not a physical display-paint measurement.',
      'Heap and DOM metrics are Chromium CDP samples after explicit garbage collection; they are not process/native memory totals.',
      'Dataset is synthetic: 200 private work summaries, eight 25-item pages, and one actor-scoped state read per work.',
    ],
  };
  const markdown = [
    '# Account Library performance baseline',
    '',
    `- Generated: ${result.generatedAt}`,
    `- Browser/host: ${result.environment.browserProject}; ${result.environment.host.operatingSystem}; ${result.environment.host.architecture}; ${result.environment.host.physicalMemoryGiB} GiB RAM; ${result.environment.host.cpuModel}`,
    `- Dataset: ${libraryWorkCount} private works across ${libraryWorkCount / libraryPageSize} pages of ${libraryPageSize}; one mocked work-state read per work`,
    `- First-navigation DOM-ready (one cold sample): ${result.measurements.firstNavigationDomReadyMs.toFixed(1)} ms; warm-reload DOM-ready p95 (${initialLoadSamplesMs.length - 1} samples): ${result.measurements.warmNavigationDomReadyP95Ms.toFixed(1)} ms`,
    `- Playwright driver wall (reported separately): first navigation ${result.measurements.firstNavigationDriverWallMs.toFixed(1)} ms; warm-reload p95 ${result.measurements.warmNavigationDriverWallP95Ms.toFixed(1)} ms`,
    `- Browser page-append DOM-update p95 (${pageAppendSamplesMs.length} samples): ${result.measurements.appendPageP95Ms.toFixed(1)} ms; Playwright driver-wall p95: ${result.measurements.appendPageDriverWallP95Ms.toFixed(1)} ms`,
    `- Browser status-filter DOM-update: ${filterMs.toFixed(1)} ms; Playwright driver wall: ${filterDriverWallMs.toFixed(1)} ms`,
    `- Chromium JS heap after GC: ${heapDeltaMiB.toFixed(1)} MiB; DOM nodes: ${domNodeCount.toLocaleString('en-US')}`,
    `- Mobile 360×800 document width: ${mobileDimensions.documentWidth}px / viewport ${mobileDimensions.viewportWidth}px; overflow: none`,
    '',
    'Scope: production frontend with deterministic API route mocks. These numbers exclude backend/PostgreSQL, real network, other browsers/devices, screen-reader output and production traffic. This is a local baseline, not an approved SLO.',
    '',
    'Raw samples and host metadata are in `account-library.json`.',
  ].join('\n');
  await writeProductionEvidence(testInfo, [{ name: 'account-library', markdown, json: result }]);
  await session.detach();
});

test('public catalog paginates 200 works and measures search over a 1,000-work synthetic index', async ({
  page,
  browserName,
}, testInfo) => {
  const works = Array.from({ length: catalogWorkCount }, (_, index) => ({
    id: uuid(index + 1, '10000000'),
    title: `Novel ${String(index).padStart(4, '0')}`,
    author: `Author ${String(index).padStart(4, '0')}`,
    sourceLanguage: 'vi',
    readyChapterCount: 1 + (index % 40),
  }));
  const requests: URL[] = [];
  const mockResponseDurationsMs: Array<{ query: string; durationMs: number }> = [];
  await installBrowserDomTiming(page, 'catalog', catalogPageSize);
  await page.addInitScript(() => {
    const target = window as CatalogTimingWindow;
    target.__catalogLongTasks = [];
    target.__catalogSearchDomDurations = [];
    if (typeof PerformanceObserver !== 'undefined') {
      const observer = new PerformanceObserver((list) => {
        const entries = target.__catalogLongTasks ?? [];
        for (const entry of list.getEntries()) {
          entries.push({ startTime: entry.startTime, duration: entry.duration });
        }
        target.__catalogLongTasks = entries;
      });
      try {
        observer.observe({ type: 'longtask', buffered: true });
      } catch {
        // Long-task timing is optional diagnostic data; the functional performance run still works.
      }
    }
    document.addEventListener(
      'submit',
      (event) => {
        const form = event.target;
        if (!(form instanceof HTMLFormElement) || !form.matches('.public-catalog-filters')) return;
        const query =
          form
            .querySelector<HTMLInputElement>('#public-catalog-query')
            ?.value.trim()
            .normalize('NFKC')
            .toLowerCase() ?? '';
        const language =
          form
            .querySelector<HTMLInputElement>('#public-catalog-language')
            ?.value.trim()
            .toLowerCase() ?? '';
        const sequence = (target.__catalogSearchSequence ?? 0) + 1;
        target.__catalogSearchSequence = sequence;
        const startMark = `catalog-search-ui-start-${sequence}`;
        performance.mark(startMark);
        const observer = new MutationObserver(() => {
          const params = new URL(window.location.href).searchParams;
          const cards = document.querySelectorAll('.public-catalog-card');
          const firstTitle =
            document.querySelector('.public-catalog-card h3 a')?.textContent?.trim() ?? '';
          const announcement =
            document.querySelector('.public-catalog-announcement')?.textContent?.trim() ?? '';
          if (
            (params.get('q') ?? '') !== query ||
            (params.get('language') ?? '') !== language ||
            firstTitle !== target.__catalogExpectedFirstTitle ||
            cards.length !== 20 ||
            announcement !== '20 truyện đã được tải.'
          ) {
            return;
          }
          observer.disconnect();
          requestAnimationFrame(() => {
            const endMark = `catalog-search-dom-end-${sequence}`;
            performance.mark(endMark);
            const measure = performance.measure(
              `catalog-search-dom-${sequence}`,
              startMark,
              endMark,
            );
            target.__catalogSearchDomDurations?.push({ query, durationMs: measure.duration });
          });
        });
        observer.observe(document.documentElement, {
          childList: true,
          characterData: true,
          subtree: true,
        });
      },
      true,
    );
  });
  await page.route('**/api/v1/system/status', (route) => json(route, publicCatalogEnabledStatus));
  await page.route('**/api/v1/catalog/works**', (route) => {
    const startedAt = performance.now();
    const requestUrl = new URL(route.request().url());
    requests.push(requestUrl);
    const query = (requestUrl.searchParams.get('q') ?? '').toLowerCase();
    const language = (requestUrl.searchParams.get('language') ?? '').toLowerCase();
    const filtered = works.filter(
      (work) =>
        (!query ||
          work.title.toLowerCase().includes(query) ||
          work.author.toLowerCase().includes(query)) &&
        (!language || work.sourceLanguage === language),
    );
    const cursor = requestUrl.searchParams.get('cursor');
    const pageNumber = cursor ? Number(cursor.slice('catalog-page-'.length)) : 0;
    const start = pageNumber * catalogPageSize;
    const items = filtered.slice(start, start + catalogPageSize);
    const hasMore = start + items.length < filtered.length;
    const response = json(route, {
      items,
      nextCursor: hasMore ? `catalog-page-${pageNumber + 1}` : null,
    });
    void response.then(() => {
      if (requestUrl.searchParams.has('q')) {
        mockResponseDurationsMs.push({
          query,
          durationMs: performance.now() - startedAt,
        });
      }
    });
    return response;
  });
  await page.setViewportSize({ width: 1280, height: 720 });
  const session = await page.context().newCDPSession(page);
  await session.send('Performance.enable');
  const baselineMetrics = await session.send('Performance.getMetrics');

  const initialLoadSamplesMs: number[] = [];
  const initialLoadDriverWallSamplesMs: number[] = [];
  for (let sample = 0; sample < initialLoadSamples; sample += 1) {
    const driverStartedAt = performance.now();
    if (sample === 0) await page.goto('/');
    else await page.reload();
    await expect(page.locator('.public-catalog-card')).toHaveCount(catalogPageSize);
    await expect
      .poll(() =>
        page.evaluate(
          () => (window as CatalogTimingWindow).__catalogInitialDomDurations?.length ?? 0,
        ),
      )
      .toBe(1);
    const initialDomDurationMs = await page.evaluate(
      () => (window as CatalogTimingWindow).__catalogInitialDomDurations?.at(-1) ?? 0,
    );
    expect(initialDomDurationMs).toBeGreaterThan(0);
    initialLoadSamplesMs.push(initialDomDurationMs);
    initialLoadDriverWallSamplesMs.push(performance.now() - driverStartedAt);
  }

  const pageAppendSamplesMs: number[] = [];
  const pageAppendDriverWallSamplesMs: number[] = [];
  for (let pageNumber = 1; pageNumber < 10; pageNumber += 1) {
    const expectedCount = (pageNumber + 1) * catalogPageSize;
    await page.evaluate((appendCount) => {
      (window as CatalogTimingWindow).__catalogPendingDomAction = {
        kind: 'catalog-append',
        buttonText: 'Tải thêm truyện',
        expectedCount: appendCount,
      };
    }, expectedCount);
    const driverStartedAt = performance.now();
    await page.getByRole('button', { name: 'Tải thêm truyện' }).click();
    await expect(page.locator('.public-catalog-card')).toHaveCount(expectedCount);
    await expect
      .poll(() =>
        page.evaluate(
          () => (window as CatalogTimingWindow).__catalogDomActionDurations?.length ?? 0,
        ),
      )
      .toBe(pageNumber);
    const action = await page.evaluate(() =>
      (window as CatalogTimingWindow).__catalogDomActionDurations?.at(-1),
    );
    expect(action?.kind).toBe('catalog-append');
    const actionDurationMs = action?.durationMs ?? 0;
    expect(actionDurationMs).toBeGreaterThan(0);
    pageAppendSamplesMs.push(actionDurationMs);
    pageAppendDriverWallSamplesMs.push(performance.now() - driverStartedAt);
  }

  await session.send('HeapProfiler.enable');
  await session.send('HeapProfiler.collectGarbage');
  const metrics = await session.send('Performance.getMetrics');
  const metric = (name: string): number =>
    metrics.metrics.find((entry) => entry.name === name)?.value ?? 0;
  const baselineMetric = (name: string): number =>
    baselineMetrics.metrics.find((entry) => entry.name === name)?.value ?? 0;
  const browser = await page.evaluate(() => ({
    userAgent: navigator.userAgent,
    platform: navigator.platform,
    viewport: { width: innerWidth, height: innerHeight },
    deviceScaleFactor: devicePixelRatio,
    hardwareConcurrency: navigator.hardwareConcurrency,
    deviceMemoryGiB: 'deviceMemory' in navigator ? Number(navigator.deviceMemory) : null,
  }));
  const heapDeltaMiB =
    (metric('JSHeapUsedSize') - baselineMetric('JSHeapUsedSize')) / (1024 * 1024);
  const domNodeCount = metric('Nodes');

  const searchSamplesMs: number[] = [];
  const searchDriverWallSamplesMs: number[] = [];
  const searchLongTasks: LongTaskSample[][] = [];
  const searchbox = page.getByRole('searchbox', { name: 'Tên truyện hoặc tác giả' });
  const languageField = page.getByLabel('Ngôn ngữ nguồn');
  for (let sample = 0; sample < searchSamples; sample += 1) {
    const queryIndex = (sample + 1) % 10;
    const query = `novel ${String(queryIndex).padStart(2, '0')}`;
    const firstExpectedTitle = `Novel ${String(queryIndex * 100).padStart(4, '0')}`;
    await searchbox.fill(query);
    await languageField.fill('vi');
    await page.evaluate((title) => {
      const target = window as CatalogTimingWindow;
      target.__catalogExpectedFirstTitle = title;
      target.__catalogLongTasks = [];
    }, firstExpectedTitle);
    const driverStartedAt = performance.now();
    await page.getByRole('button', { name: 'Tìm truyện' }).click();
    await expect.poll(() => new URL(page.url()).searchParams.get('q')).toBe(query);
    await expect.poll(() => new URL(page.url()).searchParams.get('language')).toBe('vi');
    await expect(page.locator('.public-catalog-card')).toHaveCount(catalogPageSize);
    await expect(page.locator('.public-catalog-card h3 a').first()).toHaveText(firstExpectedTitle);
    await expect
      .poll(() =>
        page.evaluate(
          () => (window as CatalogTimingWindow).__catalogSearchDomDurations?.length ?? 0,
        ),
      )
      .toBe(sample + 1);
    const browserDomDurations = await page.evaluate(
      () => (window as CatalogTimingWindow).__catalogSearchDomDurations ?? [],
    );
    const searchDomDurationMs = browserDomDurations.at(-1)?.durationMs ?? 0;
    expect(searchDomDurationMs).toBeGreaterThan(0);
    searchSamplesMs.push(searchDomDurationMs);
    searchDriverWallSamplesMs.push(performance.now() - driverStartedAt);
    searchLongTasks.push(
      await page.evaluate(() => {
        const target = window as CatalogTimingWindow;
        return target.__catalogLongTasks ?? [];
      }),
    );
  }

  await page.setViewportSize({ width: 360, height: 800 });
  await expectNoAxeViolations(page);
  const mobileDimensions = await page.evaluate(() => ({
    documentWidth: document.documentElement.scrollWidth,
    viewportWidth: document.documentElement.clientWidth,
  }));
  expect(mobileDimensions.documentWidth).toBeLessThanOrEqual(mobileDimensions.viewportWidth);
  expect(requests).toHaveLength(initialLoadSamples + 9 + searchSamples);
  expect(mockResponseDurationsMs).toHaveLength(searchSamples);

  const result = {
    generatedAt: new Date().toISOString(),
    environment: {
      ...browser,
      browserProject: `${testInfo.project.name || 'default'} (${browserName})`,
      host: {
        operatingSystem: `${platform()} ${release()}`,
        architecture: arch(),
        physicalMemoryGiB: Number((totalmem() / 1024 ** 3).toFixed(1)),
        cpuModel: cpus()[0]?.model ?? 'unknown',
      },
      playwright: '1.65.0 (pinned @playwright/test)',
    },
    dataset: {
      indexedWorks: catalogWorkCount,
      catalogPageSize,
      worksAppended: catalogPageSize * 10,
      initialLoadSamples: initialLoadSamplesMs.length,
      appendPageSamples: pageAppendSamplesMs.length,
      searchSamples: searchSamplesMs.length,
      resultCountPerSearchPage: catalogPageSize,
    },
    measurements: {
      initialLoadSamplesMs,
      firstNavigationDomReadyMs: initialLoadSamplesMs[0] ?? 0,
      warmNavigationDomReadyP95Ms: percentile95(initialLoadSamplesMs.slice(1)),
      firstNavigationDriverWallMs: initialLoadDriverWallSamplesMs[0] ?? 0,
      warmNavigationDriverWallP95Ms: percentile95(initialLoadDriverWallSamplesMs.slice(1)),
      initialLoadDriverWallSamplesMs,
      appendPageP95Ms: percentile95(pageAppendSamplesMs),
      appendPageSamplesMs: pageAppendSamplesMs,
      appendPageDriverWallP95Ms: percentile95(pageAppendDriverWallSamplesMs),
      appendPageDriverWallSamplesMs: pageAppendDriverWallSamplesMs,
      browserSearchDomUpdateP95Ms: percentile95(searchSamplesMs),
      browserSearchDomUpdateSamplesMs: searchSamplesMs,
      searchDriverWallP95Ms: percentile95(searchDriverWallSamplesMs),
      searchDriverWallSamplesMs,
      searchLongTasks,
      mockSearchResponseDurationsMs: mockResponseDurationsMs,
      firstSearchAfterAppending200CardsMs: searchSamplesMs[0] ?? 0,
      followUpSearchP95Ms: percentile95(searchSamplesMs.slice(1)),
      chromiumJsHeapDeltaMiB: heapDeltaMiB,
      chromiumDomNodeCount: domNodeCount,
      mobileDocumentWidth: mobileDimensions.documentWidth,
      mobileViewportWidth: mobileDimensions.viewportWidth,
    },
    scope: [
      'Production Angular build served locally from the loopback static server.',
      'The 1,000-work catalog and its query filtering/pagination are deterministic Playwright route mocks; server/database search cost is excluded.',
      'Browser render/action durations are captured inside Chromium through the next animation frame after the target DOM state; Playwright driver wall time is recorded separately. This is not a physical display-paint measurement.',
      'Ten 20-item pages (200 cards) are appended in the browser before the heap/DOM measurement.',
      'Heap and DOM metrics are Chromium CDP samples after explicit garbage collection; they are not process/native memory totals.',
    ],
  };
  const markdown = [
    '# Public catalog and search performance baseline',
    '',
    `- Generated: ${result.generatedAt}`,
    `- Browser/host: ${result.environment.browserProject}; ${result.environment.host.operatingSystem}; ${result.environment.host.architecture}; ${result.environment.host.physicalMemoryGiB} GiB RAM; ${result.environment.host.cpuModel}`,
    `- Dataset: ${catalogWorkCount.toLocaleString('en-US')} synthetic works; ten ${catalogPageSize}-item pages appended; each search matches at least 100 works`,
    `- First-navigation DOM-ready (one cold sample): ${result.measurements.firstNavigationDomReadyMs.toFixed(1)} ms; warm-reload DOM-ready p95 (${initialLoadSamplesMs.length - 1} samples): ${result.measurements.warmNavigationDomReadyP95Ms.toFixed(1)} ms`,
    `- Playwright driver wall (reported separately): first navigation ${result.measurements.firstNavigationDriverWallMs.toFixed(1)} ms; warm-reload p95 ${result.measurements.warmNavigationDriverWallP95Ms.toFixed(1)} ms`,
    `- Browser page-append DOM-update p95 (${pageAppendSamplesMs.length} samples): ${result.measurements.appendPageP95Ms.toFixed(1)} ms; Playwright driver-wall p95: ${result.measurements.appendPageDriverWallP95Ms.toFixed(1)} ms`,
    `- Browser submit-to-results DOM update p95 (${searchSamplesMs.length} samples): ${result.measurements.browserSearchDomUpdateP95Ms.toFixed(1)} ms`,
    `- First search after appending 200 cards: ${result.measurements.firstSearchAfterAppending200CardsMs.toFixed(1)} ms; follow-up search p95 (${searchSamplesMs.length - 1} samples): ${result.measurements.followUpSearchP95Ms.toFixed(1)} ms`,
    `- Playwright driver wall p95: ${result.measurements.searchDriverWallP95Ms.toFixed(1)} ms`,
    `- Mock search response p95: ${percentile95(mockResponseDurationsMs.map(({ durationMs }) => durationMs)).toFixed(2)} ms`,
    `- First search browser long tasks: ${(searchLongTasks[0] ?? []).map(({ duration }) => `${duration.toFixed(1)} ms`).join(', ') || 'none'}`,
    `- Chromium JS heap after GC: ${heapDeltaMiB.toFixed(1)} MiB; DOM nodes: ${domNodeCount.toLocaleString('en-US')}`,
    `- Mobile 360×800 document width: ${mobileDimensions.documentWidth}px / viewport ${mobileDimensions.viewportWidth}px; overflow: none; axe: 0 violations`,
    '',
    'Scope: production frontend with a synthetic 1,000-item dataset served by route mocks. Search and cursor-page computation happen in the test handler, so these timings are not API/PostgreSQL search latency, a production traffic profile or an approved SLO.',
    '',
    'Raw samples and host metadata are in `public-catalog.json`.',
  ].join('\n');
  await writeProductionEvidence(testInfo, [{ name: 'public-catalog', markdown, json: result }]);
  await session.detach();
});
