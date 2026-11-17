import { mkdir, writeFile } from 'node:fs/promises';
import { arch, cpus, platform, release, totalmem } from 'node:os';
import { resolve } from 'node:path';
import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const workId = '66666666-6666-4666-8666-666666666666';
const libraryId = '33333333-3333-4333-8333-333333333333';
const chapterIds = [
  '77777777-7777-4777-8777-777777777777',
  '88888888-8888-4888-8888-888888888888',
  '99999999-9999-4999-8999-999999999999',
];
const revisionId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const longSegmentCount = 5_000;
const complexSegmentCount = 800;
const shortSegmentCount = 5;
const pageSize = 1_000;
const evidenceDirectory = resolve(
  process.cwd(),
  '../../.ai/evidence/reader-performance-2026-11-17-timing-correction',
);

interface ReaderSegment {
  anchor: string;
  type: 'paragraph' | 'heading' | 'quote';
  sourceText: string;
}

interface BenchmarkResult {
  generatedAt: string;
  environment: {
    userAgent: string;
    platform: string;
    host: {
      operatingSystem: string;
      architecture: string;
      physicalMemoryGiB: number;
      cpuModel: string;
    };
    viewport: { width: number; height: number };
    deviceScaleFactor: number;
    hardwareConcurrency: number;
    deviceMemoryGiB: number | null;
    playwright: string;
    browserProject: string;
  };
  dataset: {
    longSegmentCount: number;
    longSourceTextUtf8Bytes: number;
    complexSegmentCount: number;
    shortSegmentCount: number;
    contentPageSize: number;
    longContentPageRequests: number;
  };
  measurements: {
    initialLongChapterP95Ms: number;
    initialLongChapterSamplesMs: number[];
    initialLongChapterDriverWallP95Ms: number;
    initialLongChapterDriverWallSamplesMs: number[];
    firstNavigationDomReadyMs: number;
    warmNavigationDomReadyP95Ms: number;
    chapterSwitchP95Ms: number;
    chapterSwitchSamplesMs: number[];
    chapterSwitchDriverWallP95Ms: number;
    chapterSwitchDriverWallSamplesMs: number[];
    wheelToNextAnimationFrameP95Ms: number;
    wheelToNextAnimationFrameSamplesMs: number[];
    chromiumJsHeapDeltaMiB: number;
    chromiumDomNodeCount: number;
    mobileViewport: { width: number; height: number };
    mobileDocumentWidth: number;
    mobileViewportWidth: number;
  };
  scope: string[];
}

type ReaderTimingWindow = Window & {
  __readerInitialDomDurations?: number[];
  __readerPendingChapterAction?: {
    buttonSelector: string;
    expectedTitle: string;
    expectedCount: number;
  };
  __readerActiveChapterAction?: {
    expectedTitle: string;
    expectedCount: number;
    startTime: number;
    completionScheduled: boolean;
  };
  __readerChapterSwitchDurations?: number[];
};

function metricValue(
  metrics: { metrics: Array<{ name: string; value: number }> },
  name: string,
): number {
  return metrics.metrics.find((metric) => metric.name === name)?.value ?? 0;
}

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

const work = {
  id: workId,
  libraryId,
  title: 'Thử tải Reader nhiều chương',
  author: 'Dữ liệu tổng hợp',
  language: 'vi',
  visibility: 'private',
  chapterCount: 3,
  createdAt: '2026-11-12T00:00:00Z',
};

const chapters = [
  { id: chapterIds[0], ordinal: 1, title: 'Chương dài 5.000 đoạn', accessState: 'readable' },
  { id: chapterIds[1], ordinal: 2, title: 'Chương định dạng phức hợp', accessState: 'readable' },
  { id: chapterIds[2], ordinal: 3, title: 'Chương ngắn', accessState: 'readable' },
];

function createSegments(count: number, kind: 'long' | 'complex' | 'short'): ReaderSegment[] {
  return Array.from({ length: count }, (_, index) => {
    const ordinal = String(index + 1).padStart(5, '0');
    const type =
      kind === 'complex' && index % 20 === 0
        ? 'heading'
        : kind === 'complex' && index % 5 === 0
          ? 'quote'
          : 'paragraph';
    const prefix = kind === 'long' ? `Đoạn ${ordinal}` : `${kind}-${ordinal}`;
    const sourceText =
      kind === 'long'
        ? `${prefix} — Gió lặng, cửa mở ra phía núi.`
        : kind === 'complex'
          ? `${prefix} — “Người đọc giữ lại dấu vết, lời thoại và nhịp kể.”`
          : `${prefix} — Một đoạn ngắn.`;
    return { anchor: `${kind}-p${ordinal}`, type, sourceText };
  });
}

const datasets: Record<string, ReaderSegment[]> = {
  [chapterIds[0]]: createSegments(longSegmentCount, 'long'),
  [chapterIds[1]]: createSegments(complexSegmentCount, 'complex'),
  [chapterIds[2]]: createSegments(shortSegmentCount, 'short'),
};

async function fulfillJson(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

async function installPerformanceRoutes(page: Page): Promise<{
  contentPageRequests: number[];
  unexpectedApiRequests: string[];
}> {
  const contentPageRequests = [0, 0, 0];
  const unexpectedApiRequests: string[] = [];
  const progress = {
    workId,
    chapterId: null,
    segmentAnchor: null,
    scrollRatio: 0,
    version: 0,
    updatedAt: null,
  };
  const preferences = {
    schemaVersion: 1,
    theme: 'light',
    fontFamily: 'serif',
    fontSize: 20,
    lineHeight: 1.8,
    contentWidth: 720,
  };

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const pathname = url.pathname;

    if (pathname === '/api/v1/system/status') {
      return fulfillJson(route, {
        apiVersion: '4.4.0',
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
      });
    }
    if (pathname === '/api/v1/auth/csrf') {
      return fulfillJson(route, { token: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' });
    }
    if (pathname === '/api/v1/auth/me') return fulfillJson(route, account);
    if (pathname === '/api/v1/auth/policies/current') {
      return fulfillJson(route, {
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
      });
    }
    if (pathname.startsWith('/api/v1/auth/policies/terms/')) {
      return route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Điều khoản' });
    }
    if (pathname.startsWith('/api/v1/auth/policies/privacy-notice/')) {
      return route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Quyền riêng tư' });
    }
    if (pathname === '/api/v1/me/reader-preferences') return fulfillJson(route, preferences);
    if (pathname === `/api/v1/works/${workId}/reader-manifest`) {
      return fulfillJson(route, { work, sourceLanguage: 'vi', chapters, nextCursor: null });
    }
    if (pathname === `/api/v1/works/${workId}/progress`) {
      if (request.method() === 'PUT') {
        const update = request.postDataJSON() as Record<string, unknown>;
        Object.assign(progress, update, { version: Number(progress.version) + 1 });
      }
      return fulfillJson(route, progress);
    }
    if (pathname === `/api/v1/works/${workId}/bookmarks`) {
      return fulfillJson(route, { items: [], nextCursor: null });
    }
    if (pathname === `/api/v1/works/${workId}/annotations`) {
      return fulfillJson(route, { items: [], nextCursor: null });
    }
    if (pathname.startsWith('/api/v1/chapters/') && pathname.endsWith('/content')) {
      const chapterId = pathname.split('/')[4];
      const chapterIndex = chapterIds.indexOf(chapterId);
      const segments = datasets[chapterId];
      if (!segments || chapterIndex < 0) return fulfillJson(route, { code: 'NOT_FOUND' }, 404);
      const offset = Number(url.searchParams.get('cursor') ?? 0);
      const limit = Number(url.searchParams.get('limit') ?? pageSize);
      const end = Math.min(offset + limit, segments.length);
      const page = segments.slice(offset, end);
      contentPageRequests[chapterIndex] += 1;
      return fulfillJson(route, {
        chapterId,
        workId,
        sourceRevisionId: revisionId,
        sourceLanguage: 'vi',
        segments: page,
        nextSegmentCursor: end < segments.length ? String(end) : null,
      });
    }

    unexpectedApiRequests.push(`${request.method()} ${pathname}`);
    return fulfillJson(route, { code: 'UNEXPECTED_TEST_API_REQUEST', path: pathname }, 501);
  });
  return { contentPageRequests, unexpectedApiRequests };
}

function percentile95(samples: number[]): number {
  const sorted = [...samples].sort((left, right) => left - right);
  return sorted[Math.ceil(sorted.length * 0.95) - 1] ?? 0;
}

async function measuredChapterSwitch(
  page: Page,
  direction: 'next' | 'previous',
  expectedCount: number,
  expectedTitle: string,
): Promise<{ browserDomMs: number; driverWallMs: number }> {
  const existingSamples = await page.evaluate(
    () => (window as ReaderTimingWindow).__readerChapterSwitchDurations?.length ?? 0,
  );
  await page.evaluate(
    ({ buttonSelector, title, count }) => {
      (window as ReaderTimingWindow).__readerPendingChapterAction = {
        buttonSelector,
        expectedTitle: title,
        expectedCount: count,
      };
    },
    {
      buttonSelector:
        direction === 'next'
          ? '.reader-bottom-actions button.button-primary'
          : '.reader-bottom-bar button.reader-chapter-nav',
      title: expectedTitle,
      count: expectedCount,
    },
  );
  const driverStartedAt = performance.now();
  if (direction === 'next') {
    await page.getByRole('button', { name: /Chương tiếp/ }).click();
  } else {
    await page.locator('.reader-bottom-bar button.reader-chapter-nav').click();
  }
  await expect(page.locator('#account-reader-title')).toHaveText(expectedTitle);
  await expect(page.locator('.account-reader-body [data-source-anchor]')).toHaveCount(
    expectedCount,
  );
  await expect
    .poll(() =>
      page.evaluate(
        () => (window as ReaderTimingWindow).__readerChapterSwitchDurations?.length ?? 0,
      ),
    )
    .toBe(existingSamples + 1);
  const browserDomMs = await page.evaluate(
    () => (window as ReaderTimingWindow).__readerChapterSwitchDurations?.at(-1) ?? 0,
  );
  expect(browserDomMs).toBeGreaterThan(0);
  return { browserDomMs, driverWallMs: performance.now() - driverStartedAt };
}

async function selectTextWithMouse(
  page: Page,
  anchor: string,
  selectedText: string,
): Promise<void> {
  const segment = page.locator(`[data-source-anchor="${anchor}"]`);
  const coordinates = await segment.evaluate((element, quote) => {
    const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
    let textNode = walker.nextNode() as Text | null;
    while (textNode && !textNode.data.includes(quote)) textNode = walker.nextNode() as Text | null;
    if (!textNode) throw new Error('Mouse selection fixture text is missing');
    const start = textNode.data.indexOf(quote);
    const range = document.createRange();
    range.setStart(textNode, start);
    range.setEnd(textNode, start + quote.length);
    const rect = range.getBoundingClientRect();
    return {
      start: { x: rect.left + 1, y: rect.top + rect.height / 2 },
      end: { x: rect.right - 1, y: rect.top + rect.height / 2 },
    };
  }, selectedText);
  await page.mouse.move(coordinates.start.x, coordinates.start.y);
  await page.mouse.down();
  await page.mouse.move(coordinates.end.x + 2, coordinates.end.y, { steps: 8 });
  await page.mouse.up();
}

test('production Reader renders and remains usable with short, complex, and 5,000-segment chapters', async ({
  page,
  browserName,
}, testInfo) => {
  test.setTimeout(120_000);
  const longSourceTextUtf8Bytes = datasets[chapterIds[0]].reduce(
    (bytes, segment) => bytes + Buffer.byteLength(segment.sourceText, 'utf8'),
    0,
  );
  expect(longSourceTextUtf8Bytes).toBeLessThanOrEqual(384 * 1024);
  const apiRequests = await installPerformanceRoutes(page);
  const initialViewport = page.viewportSize();
  expect(initialViewport).not.toBeNull();
  await page.addInitScript(
    ({ expectedTitle, expectedCount }) => {
      const target = window as ReaderTimingWindow;
      const navigationStartedAt = performance.now();
      target.__readerInitialDomDurations = [];
      target.__readerChapterSwitchDurations = [];
      let initialCompletionScheduled = false;

      const isReady = (title: string, count: number): boolean =>
        document.querySelector('#account-reader-title')?.textContent?.trim() === title &&
        document.querySelectorAll('.account-reader-body [data-source-anchor]').length === count;

      const scheduleInitialMeasurement = (): void => {
        if (
          initialCompletionScheduled ||
          (target.__readerInitialDomDurations?.length ?? 0) > 0 ||
          !isReady(expectedTitle, expectedCount)
        ) {
          return;
        }
        initialCompletionScheduled = true;
        requestAnimationFrame(() => {
          initialCompletionScheduled = false;
          if (!isReady(expectedTitle, expectedCount)) return;
          target.__readerInitialDomDurations?.push(performance.now() - navigationStartedAt);
        });
      };

      const scheduleChapterMeasurement = (): void => {
        const action = target.__readerActiveChapterAction;
        if (
          !action ||
          action.completionScheduled ||
          !isReady(action.expectedTitle, action.expectedCount)
        ) {
          return;
        }
        action.completionScheduled = true;
        requestAnimationFrame(() => {
          if (target.__readerActiveChapterAction !== action) return;
          action.completionScheduled = false;
          if (!isReady(action.expectedTitle, action.expectedCount)) return;
          target.__readerChapterSwitchDurations?.push(performance.now() - action.startTime);
          target.__readerActiveChapterAction = undefined;
        });
      };

      const observer = new MutationObserver(() => {
        scheduleInitialMeasurement();
        scheduleChapterMeasurement();
      });
      observer.observe(document, { childList: true, characterData: true, subtree: true });
      document.addEventListener(
        'click',
        (event) => {
          const pending = target.__readerPendingChapterAction;
          const eventTarget = event.target;
          if (!pending || !(eventTarget instanceof Element)) return;
          if (!eventTarget.closest(pending.buttonSelector)) return;
          target.__readerPendingChapterAction = undefined;
          target.__readerActiveChapterAction = {
            expectedTitle: pending.expectedTitle,
            expectedCount: pending.expectedCount,
            startTime: performance.now(),
            completionScheduled: false,
          };
          scheduleChapterMeasurement();
        },
        true,
      );
      scheduleInitialMeasurement();
    },
    { expectedTitle: chapters[0].title, expectedCount: longSegmentCount },
  );

  const session = await page.context().newCDPSession(page);
  await session.send('Performance.enable');
  const baselineMetrics = await session.send('Performance.getMetrics');

  const initialDriverStartedAt = performance.now();
  await page.goto(`/app/reader/${workId}/${chapterIds[0]}`);
  await expect(page.getByRole('heading', { name: chapters[0].title })).toBeVisible();
  await expect(page.locator('.account-reader-body [data-source-anchor]')).toHaveCount(
    longSegmentCount,
  );
  await expect
    .poll(() =>
      page.evaluate(() => (window as ReaderTimingWindow).__readerInitialDomDurations?.length ?? 0),
    )
    .toBe(1);
  const initialLongChapterMs = await page.evaluate(
    () => (window as ReaderTimingWindow).__readerInitialDomDurations?.at(-1) ?? 0,
  );
  expect(initialLongChapterMs).toBeGreaterThan(0);
  const initialLongChapterDriverWallMs = performance.now() - initialDriverStartedAt;
  const pageRequestsAfterInitialLoad = apiRequests.contentPageRequests[0];
  expect(pageRequestsAfterInitialLoad).toBe(5);
  await session.send('HeapProfiler.enable');
  await session.send('HeapProfiler.collectGarbage');
  const singleChapterMetrics = await session.send('Performance.getMetrics');
  const chromiumJsHeapDeltaMiB =
    (metricValue(singleChapterMetrics, 'JSHeapUsedSize') -
      metricValue(baselineMetrics, 'JSHeapUsedSize')) /
    (1024 * 1024);
  const chromiumDomNodeCount = metricValue(singleChapterMetrics, 'Nodes');
  const initialLongChapterSamplesMs = [initialLongChapterMs];
  const initialLongChapterDriverWallSamplesMs = [initialLongChapterDriverWallMs];
  for (let reload = 0; reload < 9; reload += 1) {
    const driverStartedAt = performance.now();
    await page.reload();
    await expect(page.getByRole('heading', { name: chapters[0].title })).toBeVisible();
    await expect(page.locator('.account-reader-body [data-source-anchor]')).toHaveCount(
      longSegmentCount,
    );
    await expect
      .poll(() =>
        page.evaluate(
          () => (window as ReaderTimingWindow).__readerInitialDomDurations?.length ?? 0,
        ),
      )
      .toBe(1);
    const navigationDomMs = await page.evaluate(
      () => (window as ReaderTimingWindow).__readerInitialDomDurations?.at(-1) ?? 0,
    );
    expect(navigationDomMs).toBeGreaterThan(0);
    initialLongChapterSamplesMs.push(navigationDomMs);
    initialLongChapterDriverWallSamplesMs.push(performance.now() - driverStartedAt);
  }

  const warmupSamples = [
    await measuredChapterSwitch(page, 'next', complexSegmentCount, chapters[1].title),
    await measuredChapterSwitch(page, 'next', shortSegmentCount, chapters[2].title),
    await measuredChapterSwitch(page, 'previous', complexSegmentCount, chapters[1].title),
    await measuredChapterSwitch(page, 'previous', longSegmentCount, chapters[0].title),
  ];
  expect(warmupSamples.every((sample) => sample.browserDomMs > 0)).toBe(true);

  const chapterSwitchSamplesMs: number[] = [];
  const chapterSwitchDriverWallSamplesMs: number[] = [];
  for (let cycle = 0; cycle < 6; cycle += 1) {
    for (const [direction, count, title] of [
      ['next', complexSegmentCount, chapters[1].title],
      ['next', shortSegmentCount, chapters[2].title],
      ['previous', complexSegmentCount, chapters[1].title],
      ['previous', longSegmentCount, chapters[0].title],
    ] as const) {
      const sample = await measuredChapterSwitch(page, direction, count, title);
      chapterSwitchSamplesMs.push(sample.browserDomMs);
      chapterSwitchDriverWallSamplesMs.push(sample.driverWallMs);
    }
  }

  await measuredChapterSwitch(page, 'next', complexSegmentCount, chapters[1].title);
  const complexHeadingCount = await page
    .locator('.account-reader-body h2[data-source-anchor]')
    .count();
  const complexQuoteCount = await page
    .locator('.account-reader-body blockquote[data-source-anchor]')
    .count();
  expect(complexHeadingCount).toBeGreaterThan(0);
  expect(complexQuoteCount).toBeGreaterThan(0);
  await expectNoAxeViolations(page);
  await measuredChapterSwitch(page, 'previous', longSegmentCount, chapters[0].title);

  await page.evaluate(() => {
    const scroller = document.querySelector<HTMLElement>('.account-reader-content');
    if (!scroller) throw new Error('Reader scroll container is missing');
    const target = window as Window & { readerWheelFrameSamples?: number[] };
    target.readerWheelFrameSamples = [];
    scroller.addEventListener(
      'wheel',
      (event) => {
        const started = event.timeStamp;
        requestAnimationFrame(() =>
          target.readerWheelFrameSamples?.push(performance.now() - started),
        );
      },
      { passive: true },
    );
  });
  const scrollerBox = await page.locator('.account-reader-content').boundingBox();
  expect(scrollerBox).not.toBeNull();
  const wheelToNextAnimationFrameSamplesMs: number[] = [];
  for (let sample = 0; sample < 20; sample += 1) {
    await page.mouse.move(
      scrollerBox!.x + scrollerBox!.width / 2,
      scrollerBox!.y + scrollerBox!.height / 2,
    );
    await page.mouse.wheel(0, sample % 2 === 0 ? 500 : -500);
    await expect
      .poll(() =>
        page.evaluate(
          () =>
            (window as Window & { readerWheelFrameSamples?: number[] }).readerWheelFrameSamples
              ?.length ?? 0,
        ),
      )
      .toBe(sample + 1);
  }
  wheelToNextAnimationFrameSamplesMs.push(
    ...(await page.evaluate(
      () =>
        (window as Window & { readerWheelFrameSamples?: number[] }).readerWheelFrameSamples ?? [],
    )),
  );

  await page.locator('.account-reader-content').evaluate((element) => {
    element.scrollTop = 0;
  });
  await page.locator('[data-source-anchor="long-p00001"]').scrollIntoViewIfNeeded();
  await selectTextWithMouse(page, 'long-p00001', 'Gió lặng');
  await expect
    .poll(() => page.evaluate(() => window.getSelection()?.toString() ?? ''))
    .toBe('Gió lặng');

  await page.setViewportSize({ width: 360, height: 800 });
  await expect(page.locator('.account-reader-body [data-source-anchor]')).toHaveCount(
    longSegmentCount,
  );
  const mobileDimensions = await page.evaluate(() => ({
    documentWidth: document.documentElement.scrollWidth,
    viewportWidth: document.documentElement.clientWidth,
  }));
  expect(mobileDimensions.documentWidth).toBeLessThanOrEqual(mobileDimensions.viewportWidth);
  await expectNoAxeViolations(page);
  expect(apiRequests.unexpectedApiRequests).toEqual([]);

  const environment = await page.evaluate(() => ({
    userAgent: navigator.userAgent,
    platform: navigator.userAgentData?.platform ?? navigator.platform,
    viewport: { width: innerWidth, height: innerHeight },
    deviceScaleFactor: devicePixelRatio,
    hardwareConcurrency: navigator.hardwareConcurrency,
    deviceMemoryGiB: 'deviceMemory' in navigator ? Number(navigator.deviceMemory) : null,
  }));
  const result: BenchmarkResult = {
    generatedAt: new Date().toISOString(),
    environment: {
      ...environment,
      host: {
        operatingSystem: `${platform()} ${release()}`,
        architecture: arch(),
        physicalMemoryGiB: Number((totalmem() / 1024 ** 3).toFixed(1)),
        cpuModel: cpus()[0]?.model ?? 'unknown',
      },
      viewport: initialViewport!,
      playwright: '1.65.0 (pinned @playwright/test)',
      browserProject: `${testInfo.project.name || 'default'} (${browserName})`,
    },
    dataset: {
      longSegmentCount,
      longSourceTextUtf8Bytes,
      complexSegmentCount,
      shortSegmentCount,
      contentPageSize: pageSize,
      longContentPageRequests: pageRequestsAfterInitialLoad,
    },
    measurements: {
      initialLongChapterP95Ms: percentile95(initialLongChapterSamplesMs),
      initialLongChapterSamplesMs,
      initialLongChapterDriverWallP95Ms: percentile95(initialLongChapterDriverWallSamplesMs),
      initialLongChapterDriverWallSamplesMs,
      firstNavigationDomReadyMs: initialLongChapterSamplesMs[0] ?? 0,
      warmNavigationDomReadyP95Ms: percentile95(initialLongChapterSamplesMs.slice(1)),
      chapterSwitchP95Ms: percentile95(chapterSwitchSamplesMs),
      chapterSwitchSamplesMs,
      chapterSwitchDriverWallP95Ms: percentile95(chapterSwitchDriverWallSamplesMs),
      chapterSwitchDriverWallSamplesMs,
      wheelToNextAnimationFrameP95Ms: percentile95(wheelToNextAnimationFrameSamplesMs),
      wheelToNextAnimationFrameSamplesMs,
      chromiumJsHeapDeltaMiB,
      chromiumDomNodeCount,
      mobileViewport: { width: 360, height: 800 },
      mobileDocumentWidth: mobileDimensions.documentWidth,
      mobileViewportWidth: mobileDimensions.viewportWidth,
    },
    scope: [
      'Production Angular build served from a local loopback-only static server.',
      'All Reader/account APIs are deterministic Playwright route mocks; no backend or PostgreSQL latency is included.',
      'Initial/chapter-switch DOM timings are captured inside Chromium through the next animation frame after the expected title and segment count are present; Playwright driver wall time is recorded separately. This is not a physical display-paint measurement.',
      'Browser memory is Chromium CDP JavaScript heap delta from an about:blank baseline after one 5,000-segment load and explicit garbage collection; it excludes native DOM, GPU, and process-wide memory.',
      'Scroll latency is wheel-event to next requestAnimationFrame, not physical display scan-out.',
      'Selection is exercised with a real mouse drag; axe scans run on the complex and 360×800 long-chapter states.',
    ],
  };

  await mkdir(evidenceDirectory, { recursive: true });
  await writeFile(
    resolve(evidenceDirectory, 'result.json'),
    `${JSON.stringify(result, null, 2)}\n`,
  );
  await writeFile(
    resolve(evidenceDirectory, 'result.md'),
    [
      '# Reader large-chapter performance evidence',
      '',
      `- Generated: ${result.generatedAt}`,
      `- Browser: ${result.environment.browserProject}`,
      `- OS/platform: ${result.environment.platform}`,
      `- Host: ${result.environment.host.operatingSystem}; ${result.environment.host.architecture}; ${result.environment.host.physicalMemoryGiB} GiB RAM; ${result.environment.host.cpuModel}`,
      `- Initial viewport: ${result.environment.viewport.width}×${result.environment.viewport.height}; DPR ${result.environment.deviceScaleFactor}`,
      `- Long chapter: ${longSegmentCount.toLocaleString('en-US')} segments, ${longSourceTextUtf8Bytes.toLocaleString('en-US')} UTF-8 source-text bytes, ${pageRequestsAfterInitialLoad} API pages of ${pageSize.toLocaleString('en-US')}`,
      `- Browser initial DOM-ready p95 (${initialLongChapterSamplesMs.length} samples): ${result.measurements.initialLongChapterP95Ms.toFixed(1)} ms; first navigation: ${result.measurements.firstNavigationDomReadyMs.toFixed(1)} ms; warm-navigation p95: ${result.measurements.warmNavigationDomReadyP95Ms.toFixed(1)} ms; Playwright driver-wall p95: ${result.measurements.initialLongChapterDriverWallP95Ms.toFixed(1)} ms`,
      `- Browser chapter-switch DOM-update p95 (24 samples after warm-up): ${result.measurements.chapterSwitchP95Ms.toFixed(1)} ms; Playwright driver-wall p95: ${result.measurements.chapterSwitchDriverWallP95Ms.toFixed(1)} ms`,
      `- Wheel event to next animation frame p95 (20 samples): ${result.measurements.wheelToNextAnimationFrameP95Ms.toFixed(1)} ms`,
      `- Chromium JS heap delta from blank page: ${chromiumJsHeapDeltaMiB.toFixed(1)} MiB; DOM nodes: ${chromiumDomNodeCount.toLocaleString('en-US')}`,
      `- Mobile 360×800 document width: ${mobileDimensions.documentWidth}px / viewport ${mobileDimensions.viewportWidth}px; overflow: none`,
      '',
      'All three representative fixtures were rendered; a real mouse drag selected “Gió lặng”; axe scans passed on the mixed-format chapter and on the 360×800 long-chapter state.',
      '',
      'Scope: production frontend with deterministic mocked account/Reader APIs. These results do not include backend/PostgreSQL latency, another browser/OS/device, screen-reader output, or physical display scan-out. The local evidence is a baseline, not an approved release SLO.',
      '',
      'Raw measurements and full environment string are in `result.json`.',
    ].join('\n') + '\n',
  );

  expect(chapterSwitchSamplesMs).toHaveLength(24);
  expect(wheelToNextAnimationFrameSamplesMs).toHaveLength(20);
});
