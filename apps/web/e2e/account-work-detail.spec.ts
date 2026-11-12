import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const workId = '22222222-2222-4222-8222-222222222222';
const libraryId = '33333333-3333-4333-8333-333333333333';
const chapters = [
  {
    id: '44444444-4444-4444-8444-444444444444',
    ordinal: 1,
    title: 'Rời khỏi thôn',
    accessState: 'readable',
  },
  {
    id: '55555555-5555-4555-8555-555555555555',
    ordinal: 2,
    title: 'Chương chưa khả dụng',
    accessState: 'unavailable',
  },
  {
    id: '66666666-6666-4666-8666-666666666666',
    ordinal: 3,
    title: 'Đến chân núi',
    accessState: 'readable',
  },
];
const work = {
  id: workId,
  libraryId,
  title: 'Sơn Hà',
  author: 'Minh An',
  language: 'vi',
  visibility: 'private',
  chapterCount: chapters.length,
  createdAt: '2026-11-12T10:00:00Z',
};

async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

async function installAccountSession(
  page: Page,
  options: { manifestStatus?: number; noProgress?: boolean } = {},
): Promise<{ manifestReads: number }> {
  let manifestReads = 0;
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '4.2.1',
      environment: 'test',
      integrations: {
        googleOidcEnabled: false,
        momoTestEnabled: false,
        momoEnvironment: 'disabled',
      },
      features: {
        importProcessingEnabled: true,
        cultivationEnabled: false,
        commerceSandboxEnabled: false,
      },
    }),
  );
  await page.route('**/api/v1/auth/me', (route) =>
    json(route, {
      user: {
        id: 'user-1',
        displayName: 'Độc giả',
        contactEmail: 'reader@example.test',
        avatarUrl: null,
        status: 'ACTIVE',
      },
      policy: {
        termsAcceptedVersion: 'terms-1',
        privacyNoticeAcknowledgedVersion: 'privacy-1',
        requiredAcceptanceComplete: true,
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
  await page.route(`**/api/v1/works/${workId}/reader-manifest**`, (route) => {
    manifestReads += 1;
    if (options.manifestStatus) {
      return json(route, { code: 'UNAUTHENTICATED' }, options.manifestStatus);
    }
    const cursor = new URL(route.request().url()).searchParams.get('cursor');
    const items = cursor ? [chapters[2]] : chapters.slice(0, 2);
    return json(route, {
      work,
      sourceLanguage: work.language,
      chapters: items,
      nextCursor: cursor ? null : 'manifest-next-page',
    });
  });
  await page.route(`**/api/v1/works/${workId}/progress`, (route) =>
    options.noProgress
      ? json(route, { code: 'READER_PROGRESS_NOT_FOUND' }, 404)
      : json(route, {
          workId,
          chapterId: chapters[2].id,
          segmentAnchor: 'opening-line',
          scrollRatio: 0.35,
          version: 4,
          updatedAt: '2026-11-13T08:00:00Z',
        }),
  );
  return {
    get manifestReads() {
      return manifestReads;
    },
  };
}

test('account Work Detail loads private metadata and paginates into the saved readable chapter', async ({
  page,
}) => {
  const api = await installAccountSession(page);
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto(`/app/works/${workId}`);

  await expect(page.getByRole('heading', { name: 'Sơn Hà' })).toBeVisible();
  await expect(page.getByText('Minh An')).toBeVisible();
  await expect(page.getByText(/Mục lục không chứa nội dung chương/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Tiếp tục đọc' })).toBeEnabled();
  await expect(page.getByRole('link', { name: /Rời khỏi thôn/ })).toBeVisible();
  await expect(page.getByRole('link', { name: /Chương chưa khả dụng/ })).toHaveCount(0);
  await expectNoAxeViolations(page);

  await page.getByRole('button', { name: 'Tải thêm chương' }).click();
  await expect(page.getByRole('link', { name: /Đến chân núi/ })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Tải thêm chương' })).toHaveCount(0);
  const dimensions = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);

  await page.getByRole('button', { name: 'Tiếp tục đọc' }).click();
  await expect(page).toHaveURL(new RegExp(`/app/reader/${workId}/${chapters[2].id}$`));
  expect(api.manifestReads).toBeGreaterThanOrEqual(2);
});

test('account Work Detail offers sign-in recovery and preserves its return route', async ({
  page,
}) => {
  await installAccountSession(page, { manifestStatus: 401 });
  await page.goto(`/app/works/${workId}`);

  await expect(page.getByRole('heading', { name: 'Chưa mở được tác phẩm' })).toBeVisible();
  const signIn = page.getByRole('link', { name: 'Đăng nhập lại' });
  await expect(signIn).toHaveAttribute('href', `/sign-in?returnTo=%2Fapp%2Fworks%2F${workId}`);
  await expectNoAxeViolations(page);
});

test('account Work Detail chooses the first readable chapter without saved progress', async ({
  page,
}) => {
  await installAccountSession(page, { noProgress: true });
  await page.goto(`/app/works/${workId}`);

  const start = page.getByRole('button', { name: 'Đọc từ đầu' });
  await expect(start).toBeEnabled();
  await start.click();
  await expect(page).toHaveURL(new RegExp(`/app/reader/${workId}/${chapters[0].id}$`));
});
