import { mkdir, writeFile } from 'node:fs/promises';
import { arch, cpus, platform, totalmem } from 'node:os';
import { resolve } from 'node:path';
import { expect, test } from './support';
import type { Page, Route } from '@playwright/test';

const importId = '44444444-4444-4444-8444-444444444444';
const libraryId = '33333333-3333-4333-8333-333333333333';
const translationJobId = '66666666-6666-4666-8666-666666666666';
const paymentOrderId = '11111111-1111-4111-8111-111111111111';
const chapterCount = 2_000;
const pageSize = 100;
const translationPollSamples = 6;
const paymentRefreshSamples = 10;
const strongEtag = `"${'a'.repeat(43)}"`;
const evidenceDirectory = resolve(
  process.cwd(),
  '../../.ai/evidence/supplemental-flow-performance-2026-11-25',
);

interface FlowMeasurement {
  flow: string;
  firstVisibleMs: number;
  interactionSamplesMs: number[];
  interactionP95Ms: number;
  workload: Record<string, number | string>;
  scope: string;
}

interface PerformanceWindow extends Window {
  __flowNavigationStart?: number;
}

const measurements: FlowMeasurement[] = [];

test.afterAll(async () => {
  if (measurements.length === 0) return;
  await mkdir(evidenceDirectory, { recursive: true });
  const report = {
    generatedAt: new Date().toISOString(),
    environment: {
      platform: platform(),
      architecture: arch(),
      cpuModel: cpus()[0]?.model ?? 'unknown',
      logicalCpuCount: cpus().length,
      totalMemoryGiB: Number((totalmem() / 1024 ** 3).toFixed(2)),
      nodeVersion: process.version,
      chromiumUserAgent: 'recorded per test result in Playwright trace when available',
      viewport: 'configured by the Playwright performance project',
    },
    scope:
      'Local production Angular build with deterministic Playwright API route mocks. No API, PostgreSQL, parser worker, translation provider, VNPAY merchant, network or production traffic latency is measured.',
    measurements,
  };
  await writeFile(
    resolve(evidenceDirectory, 'result.json'),
    `${JSON.stringify(report, null, 2)}\n`,
  );
  await writeFile(resolve(evidenceDirectory, 'result.md'), renderReport(report));
});

test('restores a 2,000-chapter import preview and measures client pagination', async ({ page }) => {
  await page.addInitScript((id) => {
    sessionStorage.setItem('novel-web:account-import-job:v1', id);
    (window as PerformanceWindow).__flowNavigationStart = performance.now();
  }, importId);
  await installAccountRoutes(page);

  let previewRequests = 0;
  await page.route(`**/api/v1/imports/${importId}/preview**`, async (route) => {
    previewRequests += 1;
    const params = new URL(route.request().url()).searchParams;
    const pageIndex = Number(params.get('cursor') ?? 0);
    const first = pageIndex * pageSize;
    const chapters = Array.from({ length: pageSize }, (_, index) => {
      const ordinal = first + index + 1;
      return { id: chapterId(ordinal), ordinal, title: `Chương ${ordinal}` };
    });
    await json(
      route,
      {
        importId,
        version: 1,
        title: 'Thành phố dưới chân mây',
        author: 'Tác giả thử nghiệm',
        chapterCount,
        language: 'vi',
        chapters,
        nextCursor: pageIndex + 1 < chapterCount / pageSize ? String(pageIndex + 1) : null,
        warnings: [],
      },
      200,
      { ETag: strongEtag, 'Cache-Control': 'no-store' },
    );
  });

  const driverStarted = performance.now();
  await page.goto('/app/import/review');
  await expect(page.getByRole('heading', { name: 'Kiểm tra metadata và mục lục' })).toBeVisible();
  await expect(page.locator('.chapter-pagination span')).toHaveText('Trang 1 / 40');
  await expect(page.locator('.import-chapter-list li')).toHaveCount(50);
  const firstVisibleMs = await page.evaluate(
    () => performance.now() - ((window as PerformanceWindow).__flowNavigationStart ?? 0),
  );

  const paginationSamplesMs: number[] = [];
  for (let pageNumber = 2; pageNumber <= 40; pageNumber += 1) {
    paginationSamplesMs.push(
      await page.evaluate(async () => {
        const start = performance.now();
        const next = [...document.querySelectorAll('button')].find((button) =>
          button.textContent?.includes('Trang sau'),
        );
        if (!(next instanceof HTMLButtonElement)) {
          throw new Error('Next page button was not found.');
        }
        next.click();
        await new Promise<void>((resolveFrame) =>
          requestAnimationFrame(() => requestAnimationFrame(() => resolveFrame())),
        );
        return performance.now() - start;
      }),
    );
    await expect(page.locator('.chapter-pagination span')).toHaveText(`Trang ${pageNumber} / 40`);
  }
  expect(previewRequests).toBe(20);

  measurements.push({
    flow: 'Account import preview recovery',
    firstVisibleMs,
    interactionSamplesMs: paginationSamplesMs,
    interactionP95Ms: percentile95(paginationSamplesMs),
    workload: {
      previewPagesLoaded: previewRequests,
      chapterCount,
      chaptersRenderedPerPage: 50,
      paginationSteps: paginationSamplesMs.length,
      playwrightDriverWallMs: Number((performance.now() - driverStarted).toFixed(1)),
    },
    scope:
      'Measures restoration and browser rendering from 20 mocked 100-chapter preview responses. Does not parse/upload bytes or include backend/PostgreSQL latency.',
  });
});

test('renders fake-local translation status polling under a changing job', async ({ page }) => {
  await page.addInitScript(() => {
    (window as PerformanceWindow).__flowNavigationStart = performance.now();
  });
  await installAccountRoutes(page);
  const statusSequence = [
    'queued',
    'translating',
    'translating',
    'validating',
    'translating',
    'validating',
    'review_required',
  ];
  let statusReads = 0;

  await page.route('**/api/v1/translation/jobs**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith(`/${translationJobId}`)) {
      const index = Math.min(statusReads++, statusSequence.length - 1);
      const state = statusSequence[index];
      const completedUnits = Math.min(index, 6);
      await json(route, translationStatus(state, completedUnits), 200, {
        'Cache-Control': 'no-store',
        'Retry-After': '1',
      });
      return;
    }
    await json(route, { items: [translationStatus('queued', 0)], nextCursor: null }, 200, {
      'Cache-Control': 'no-store',
    });
  });

  const driverStarted = performance.now();
  await page.goto(`/app/translation/jobs/${translationJobId}`);
  const statusHeading = page.locator('#translation-job-status-heading');
  await expect(statusHeading).toHaveText('Đang chờ xử lý thử');
  const firstVisibleMs = await page.evaluate(
    () => performance.now() - ((window as PerformanceWindow).__flowNavigationStart ?? 0),
  );

  const pollSamplesMs: number[] = [];
  for (let poll = 1; poll <= translationPollSamples; poll += 1) {
    const previousProgress = `${(poll - 1) * 10}%`;
    const nextProgress = `${poll * 10}%`;
    await expect(page.locator('.translation-job-progress-label strong')).toHaveText(
      previousProgress,
    );
    const start = await page.evaluate(() => performance.now());
    const statusResponse = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === `/api/v1/translation/jobs/${translationJobId}`,
    );
    await statusResponse;
    await expect(page.locator('.translation-job-progress-label strong')).toHaveText(nextProgress);
    pollSamplesMs.push(await page.evaluate((startedAt) => performance.now() - startedAt, start));
  }
  expect(statusReads).toBe(translationPollSamples + 1);

  measurements.push({
    flow: 'Fake-local translation status polling',
    firstVisibleMs,
    interactionSamplesMs: pollSamplesMs,
    interactionP95Ms: percentile95(pollSamplesMs),
    workload: {
      statusPolls: statusReads - 1,
      totalUnits: 10,
      apiRetryAfterSeconds: 1,
      playwrightDriverWallMs: Number((performance.now() - driverStarted).toFixed(1)),
    },
    scope:
      'Measures rendered progress after deterministic 1-second poll scheduling and mocked status responses. Does not include translation worker or provider execution.',
  });
});

test('measures repeated VNPAY TEST status refresh to visible order state', async ({ page }) => {
  await page.addInitScript(() => {
    (window as PerformanceWindow).__flowNavigationStart = performance.now();
  });
  await installAccountRoutes(page);
  let statusReads = 0;
  await page.route(`**/api/v1/payment-orders/${paymentOrderId}`, async (route) => {
    const state = statusReads++ % 2 === 0 ? 'PENDING' : 'UNKNOWN';
    await json(route, paymentOrder(state), 200, { 'Cache-Control': 'no-store' });
  });

  const driverStarted = performance.now();
  await page.goto(`/app/payment/result?orderId=${paymentOrderId}`);
  await expect(page.locator('.payment-result-badge')).toHaveAttribute('data-state', 'PENDING');
  const firstVisibleMs = await page.evaluate(
    () => performance.now() - ((window as PerformanceWindow).__flowNavigationStart ?? 0),
  );

  const refreshSamplesMs: number[] = [];
  for (let refresh = 0; refresh < paymentRefreshSamples; refresh += 1) {
    refreshSamplesMs.push(
      await page.evaluate(async () => {
        const badge = document.querySelector<HTMLElement>('.payment-result-badge');
        const button = [...document.querySelectorAll('button')].find((candidate) =>
          candidate.textContent?.includes('Kiểm tra trạng thái đã lưu'),
        );
        if (!badge || !(button instanceof HTMLButtonElement)) {
          throw new Error('Payment status control was not ready.');
        }
        const previousState = badge.dataset['state'];
        const startedAt = performance.now();
        return new Promise<number>((resolveRefresh, rejectRefresh) => {
          const observer = new MutationObserver(() => {
            if (badge.dataset['state'] !== previousState) {
              observer.disconnect();
              resolveRefresh(performance.now() - startedAt);
            }
          });
          observer.observe(badge, { attributes: true, attributeFilter: ['data-state'] });
          button.click();
          window.setTimeout(() => {
            observer.disconnect();
            rejectRefresh(new Error('Payment status did not update after the mocked response.'));
          }, 5_000);
        });
      }),
    );
    const expectedState = (refresh + 1) % 2 === 0 ? 'PENDING' : 'UNKNOWN';
    await expect(page.locator('.payment-result-badge')).toHaveAttribute(
      'data-state',
      expectedState,
    );
  }
  expect(statusReads).toBe(paymentRefreshSamples + 1);

  measurements.push({
    flow: 'VNPAY TEST payment status refresh',
    firstVisibleMs,
    interactionSamplesMs: refreshSamplesMs,
    interactionP95Ms: percentile95(refreshSamplesMs),
    workload: {
      manualStatusRefreshes: statusReads - 1,
      orderAmountVnd: 1_000,
      paidCredits: 100,
      playwrightDriverWallMs: Number((performance.now() - driverStarted).toFixed(1)),
    },
    scope:
      'Measures manual status-check request through visible state update using synthetic VNPAY TEST records. No merchant checkout/IPN, VNPAY network or payment settlement is performed.',
  });
});

async function installAccountRoutes(page: Page): Promise<void> {
  await page.route('**/api/v1/**', (route) =>
    json(route, { code: 'UNMOCKED_PERFORMANCE_ROUTE' }, 404),
  );
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '10.16.0',
      environment: 'test',
      integrations: { googleOidcEnabled: false, vnpayTestEnabled: true, vnpayEnvironment: 'TEST' },
      features: {
        importProcessingEnabled: true,
        cultivationEnabled: false,
        commerceSandboxEnabled: true,
        translationFakePreviewEnabled: true,
      },
    }),
  );
  await page.route('**/api/v1/auth/me', (route) =>
    json(route, {
      user: {
        id: '11111111-1111-4111-8111-111111111111',
        displayName: 'Độc giả hiệu năng',
        contactEmail: 'performance@example.test',
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
        commerceEnabled: true,
        cultivationEnabled: false,
      },
    }),
  );
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
  await page.route('**/api/v1/libraries**', (route) =>
    json(route, {
      items: [
        {
          id: libraryId,
          name: 'Thư viện riêng',
          visibility: 'private',
          isDefault: true,
          createdAt: '2026-11-01T00:00:00Z',
        },
      ],
      nextCursor: null,
    }),
  );
  await page.route('**/api/v1/imports/rights-statement/current', (route) =>
    json(route, {
      version: 'private-import-v1',
      effectiveAt: '2026-11-01T00:00:00Z',
      displayText: 'Tôi xác nhận có quyền xử lý tệp trong thư viện cá nhân.',
    }),
  );
  await page.route(`**/api/v1/imports/${importId}`, (route) =>
    json(route, {
      id: importId,
      status: 'ready_to_commit',
      createdAt: '2026-11-01T10:00:00Z',
      expiresAt: '2026-11-02T10:00:00Z',
      warnings: [],
    }),
  );
}

function translationStatus(status: string, completedUnits: number) {
  const completed = Math.min(completedUnits, 9);
  const active = status !== 'review_required';
  return {
    job: {
      id: translationJobId,
      workId: '33333333-3333-4333-8333-333333333333',
      chapterId: '77777777-7777-4777-8777-777777777777',
      sourceRevisionId: '88888888-8888-4888-8888-888888888888',
      sourceRevisionCurrent: true,
      workTitle: 'Thành phố dưới chân mây',
      chapterTitle: 'Chương mở đầu',
      chapterOrdinal: 1,
      canManage: true,
      sourceLanguage: 'vi',
      targetLanguage: 'en',
      providerId: 'fake-local',
      status,
      totalUnits: 10,
      completedUnits: completed,
      failedUnits: 0,
      queuedUnits: active ? 10 - completed - 1 : 0,
      runningUnits: active ? 1 : 0,
      retryWaitUnits: 0,
      cancelledUnits: 0,
      failureCode: null,
      candidateId: status === 'review_required' ? '99999999-9999-4999-8999-999999999999' : null,
      candidateStatus: status === 'review_required' ? 'ready_for_review' : null,
      createdAt: '2026-11-01T10:00:00Z',
      updatedAt: '2026-11-01T10:01:00Z',
      startedAt: '2026-11-01T10:00:01Z',
      completedAt: status === 'review_required' ? '2026-11-01T10:01:00Z' : null,
      cancelRequestedAt: null,
    },
    terminalUnits: completed,
    progressPercent: completed * 10,
  };
}

function paymentOrder(status: 'PENDING' | 'UNKNOWN') {
  return {
    id: paymentOrderId,
    packId: 'vnpay-test-credits-100',
    packVersion: 1,
    environment: 'TEST',
    currency: 'VND',
    amountVnd: 1_000,
    paidCredits: 100,
    bonusCredits: 0,
    paidExpiresAt: null,
    bonusExpiresAt: null,
    status,
    createdAt: '2026-11-01T10:00:00Z',
  };
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

function chapterId(ordinal: number): string {
  return `00000000-0000-4000-8000-${ordinal.toString(16).padStart(12, '0')}`;
}

function percentile95(values: number[]): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * 0.95) - 1)];
}

function renderReport(report: {
  generatedAt: string;
  environment: Record<string, string | number>;
  scope: string;
  measurements: FlowMeasurement[];
}): string {
  return [
    '# Supplemental browser performance baseline',
    '',
    `Generated: ${report.generatedAt}`,
    `Environment: ${report.environment.platform} ${report.environment.architecture}; ${report.environment.logicalCpuCount} logical CPUs; ${report.environment.totalMemoryGiB} GiB RAM; ${report.environment.nodeVersion}.`,
    '',
    report.scope,
    '',
    '| Flow | First visible (ms) | Interaction samples | Interaction p95 (ms) | Workload |',
    '|---|---:|---:|---:|---|',
    ...report.measurements.map(
      (item) =>
        `| ${item.flow} | ${item.firstVisibleMs.toFixed(1)} | ${item.interactionSamplesMs.length} | ${item.interactionP95Ms.toFixed(1)} | ${Object.entries(
          item.workload,
        )
          .map(([key, value]) => `${key}=${value}`)
          .join(', ')} |`,
    ),
    '',
    ...report.measurements.flatMap((item) => [`## ${item.flow}`, '', item.scope, '']),
    'No approved SLO is applied. These local mock results are an instrumentation baseline, not production capacity evidence.',
    '',
  ].join('\n');
}
