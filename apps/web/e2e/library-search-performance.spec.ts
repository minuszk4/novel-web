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
    momoTestEnabled: false,
    momoEnvironment: 'TEST',
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

async function measure(page: Page, metricName: string, startMark: string): Promise<number> {
  return page.evaluate(
    ({ name, start }) => {
      performance.clearMeasures(name);
      return performance.measure(name, start).duration;
    },
    { name: metricName, start: startMark },
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
  await page.addInitScript(() => performance.mark('library-navigation-start'));
  await page.addInitScript(() => localStorage.removeItem('novel.demo.library.v1'));

  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '9.4.0',
      environment: 'test',
      integrations: {
        googleOidcEnabled: false,
        momoTestEnabled: false,
        momoEnvironment: 'disabled',
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
  await page.setViewportSize({ width: 1280, height: 720 });
  const session = await page.context().newCDPSession(page);
  await session.send('Performance.enable');
  const baselineMetrics = await session.send('Performance.getMetrics');

  for (let sample = 0; sample < initialLoadSamples; sample += 1) {
    if (sample === 0) await page.goto('/app/library');
    else await page.reload();
    await expect(page.locator('.library-work-row')).toHaveCount(libraryPageSize);
    await expect.poll(reads.stateReads).toBe((sample + 1) * libraryPageSize);
    initialLoadSamplesMs.push(
      await measure(page, 'library-initial-render', 'library-navigation-start'),
    );
  }

  const pageAppendSamplesMs: number[] = [];
  for (let pageNumber = 1; pageNumber < libraryWorkCount / libraryPageSize; pageNumber += 1) {
    const expectedWorkCount = (pageNumber + 1) * libraryPageSize;
    await page.evaluate(() => performance.mark('library-append-start'));
    await page.getByRole('button', { name: 'Tải thêm tác phẩm' }).click();
    await expect(page.locator('.library-work-row')).toHaveCount(expectedWorkCount);
    await expect
      .poll(reads.stateReads)
      .toBe(initialLoadSamples * libraryPageSize + pageNumber * libraryPageSize);
    pageAppendSamplesMs.push(await measure(page, 'library-page-append', 'library-append-start'));
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

  await page.evaluate(() => performance.mark('library-filter-start'));
  await page.getByRole('button', { name: 'Hoàn thành 100' }).click();
  await expect(page.locator('.library-work-row')).toHaveCount(100);
  const filterMs = await measure(page, 'library-status-filter', 'library-filter-start');
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
      initialLoadP95Ms: percentile95(initialLoadSamplesMs),
      initialLoadSamplesMs,
      appendPageP95Ms: percentile95(pageAppendSamplesMs),
      appendPageSamplesMs: pageAppendSamplesMs,
      statusFilterMs: filterMs,
      chromiumJsHeapDeltaMiB: heapDeltaMiB,
      chromiumDomNodeCount: domNodeCount,
      mobileDocumentWidth: mobileDimensions.documentWidth,
      mobileViewportWidth: mobileDimensions.viewportWidth,
    },
    scope: [
      'Production Angular build served locally from the loopback static server.',
      'Account, library, work-state and policy endpoints are deterministic Playwright route mocks; PostgreSQL/API latency is excluded.',
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
    `- Initial load p95 (${initialLoadSamplesMs.length} samples): ${result.measurements.initialLoadP95Ms.toFixed(1)} ms`,
    `- Page-append p95 (${pageAppendSamplesMs.length} samples): ${result.measurements.appendPageP95Ms.toFixed(1)} ms`,
    `- Client status-filter render: ${filterMs.toFixed(1)} ms`,
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
  await page.addInitScript(() => performance.mark('catalog-navigation-start'));
  await page.route('**/api/v1/system/status', (route) => json(route, publicCatalogEnabledStatus));
  await page.route('**/api/v1/catalog/works**', (route) => {
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
    return json(route, {
      items,
      nextCursor: hasMore ? `catalog-page-${pageNumber + 1}` : null,
    });
  });
  await page.setViewportSize({ width: 1280, height: 720 });
  const session = await page.context().newCDPSession(page);
  await session.send('Performance.enable');
  const baselineMetrics = await session.send('Performance.getMetrics');

  const initialLoadSamplesMs: number[] = [];
  for (let sample = 0; sample < initialLoadSamples; sample += 1) {
    if (sample === 0) await page.goto('/');
    else await page.reload();
    await expect(page.locator('.public-catalog-card')).toHaveCount(catalogPageSize);
    initialLoadSamplesMs.push(
      await measure(page, 'catalog-initial-render', 'catalog-navigation-start'),
    );
  }

  const pageAppendSamplesMs: number[] = [];
  for (let pageNumber = 1; pageNumber < 10; pageNumber += 1) {
    const expectedCount = (pageNumber + 1) * catalogPageSize;
    await page.evaluate(() => performance.mark('catalog-append-start'));
    await page.getByRole('button', { name: 'Tải thêm truyện' }).click();
    await expect(page.locator('.public-catalog-card')).toHaveCount(expectedCount);
    pageAppendSamplesMs.push(await measure(page, 'catalog-page-append', 'catalog-append-start'));
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
  const searchbox = page.getByRole('searchbox', { name: 'Tên truyện hoặc tác giả' });
  const languageField = page.getByLabel('Ngôn ngữ nguồn');
  for (let sample = 0; sample < searchSamples; sample += 1) {
    const queryIndex = (sample + 1) % 10;
    const query = `novel ${String(queryIndex).padStart(2, '0')}`;
    const firstExpectedTitle = `Novel ${String(queryIndex * 100).padStart(4, '0')}`;
    await searchbox.fill(query);
    await languageField.fill('vi');
    await page.evaluate(() => performance.mark('catalog-search-start'));
    await page.getByRole('button', { name: 'Tìm truyện' }).click();
    await expect.poll(() => new URL(page.url()).searchParams.get('q')).toBe(query);
    await expect.poll(() => new URL(page.url()).searchParams.get('language')).toBe('vi');
    await expect(page.locator('.public-catalog-card')).toHaveCount(catalogPageSize);
    await expect(page.locator('.public-catalog-card h3 a').first()).toHaveText(firstExpectedTitle);
    searchSamplesMs.push(await measure(page, 'catalog-search-render', 'catalog-search-start'));
  }

  await page.setViewportSize({ width: 360, height: 800 });
  await expectNoAxeViolations(page);
  const mobileDimensions = await page.evaluate(() => ({
    documentWidth: document.documentElement.scrollWidth,
    viewportWidth: document.documentElement.clientWidth,
  }));
  expect(mobileDimensions.documentWidth).toBeLessThanOrEqual(mobileDimensions.viewportWidth);
  expect(requests).toHaveLength(initialLoadSamples + 9 + searchSamples);

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
      initialLoadP95Ms: percentile95(initialLoadSamplesMs),
      initialLoadSamplesMs,
      appendPageP95Ms: percentile95(pageAppendSamplesMs),
      appendPageSamplesMs: pageAppendSamplesMs,
      searchP95Ms: percentile95(searchSamplesMs),
      searchSamplesMs,
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
    `- Initial page p95 (${initialLoadSamplesMs.length} samples): ${result.measurements.initialLoadP95Ms.toFixed(1)} ms`,
    `- Page-append p95 (${pageAppendSamplesMs.length} samples): ${result.measurements.appendPageP95Ms.toFixed(1)} ms`,
    `- Search-to-results p95 (${searchSamplesMs.length} samples): ${result.measurements.searchP95Ms.toFixed(1)} ms`,
    `- First search after appending 200 cards: ${result.measurements.firstSearchAfterAppending200CardsMs.toFixed(1)} ms; follow-up search p95 (${searchSamplesMs.length - 1} samples): ${result.measurements.followUpSearchP95Ms.toFixed(1)} ms`,
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
