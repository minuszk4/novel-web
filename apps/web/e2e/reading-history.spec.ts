import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const workId = '66666666-6666-4666-8666-666666666666';
const chapterId = '77777777-7777-4777-8777-777777777777';
const lastReadAt = '2026-11-11T10:00:00Z';

const account = {
  user: {
    id: 'user-history-test',
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
    importEnabled: false,
    readerEnabled: true,
    commerceEnabled: false,
    cultivationEnabled: false,
  },
};

const historyItem = {
  work: {
    id: workId,
    libraryId: '33333333-3333-4333-8333-333333333333',
    title: 'Gió qua núi',
    author: 'Linh An',
    language: 'vi',
    visibility: 'private',
    chapterCount: 8,
    createdAt: '2026-11-10T10:00:00Z',
    lastReadAt,
  },
  chapter: { id: chapterId, ordinal: 3, title: 'Qua khỏi dốc' },
  progress: {
    workId,
    chapterId,
    segmentAnchor: 'chapter-3-p2',
    scrollRatio: 0.4,
    version: 4,
    updatedAt: lastReadAt,
  },
};

async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

async function installAcceptedAccountRoutes(page: Page): Promise<void> {
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: 'v1',
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
  await page.route('**/api/v1/auth/csrf', (route) =>
    json(route, { token: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' }),
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
}

const historyViewports = [
  { name: 'mobile 360×800', width: 360, height: 800 },
  { name: 'short landscape 640×360', width: 640, height: 360 },
  { name: 'desktop 1280×900', width: 1280, height: 900 },
] as const;

for (const viewport of historyViewports) {
  test(`reading history resumes, confirms deletion and clears at ${viewport.name}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await installAcceptedAccountRoutes(page);
    let deleted = false;
    await page.route('**/api/v1/me/reading-history**', async (route) => {
      if (route.request().method() === 'DELETE') {
        deleted = true;
        await route.fulfill({ status: 204 });
      } else {
        await json(route, { items: deleted ? [] : [historyItem], nextCursor: null });
      }
    });
    await page.route('**/api/v1/me/reading-history/*', async (route) => {
      if (route.request().method() === 'DELETE') {
        deleted = true;
        await route.fulfill({ status: 204 });
      } else {
        await route.fallback();
      }
    });

    await page.goto('/app/history');
    await expect(page.getByRole('heading', { name: 'Lịch sử đọc' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Gió qua núi' })).toBeVisible();
    await expect(page.getByText('Chương 3 · Qua khỏi dốc')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Tiếp tục đọc' })).toHaveAttribute(
      'href',
      `/app/reader/${workId}/${chapterId}`,
    );
    await expectNoAxeViolations(page);
    await page.screenshot({
      path: testInfo.outputPath(`01-reading-history-${viewport.width}x${viewport.height}.png`),
      fullPage: false,
    });

    await page.getByRole('button', { name: 'Xóa lịch sử đọc của Gió qua núi' }).click();
    const dialog = page.getByRole('dialog', { name: 'Xóa mục này khỏi lịch sử đọc?' });
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('vị trí đọc đã lưu');
    await expectNoAxeViolations(page);
    await page.screenshot({
      path: testInfo.outputPath(`02-delete-confirmation-${viewport.width}x${viewport.height}.png`),
      fullPage: false,
    });
    await dialog.getByRole('button', { name: 'Xóa lịch sử' }).click();
    await expect(page.getByRole('heading', { name: 'Chưa có vị trí đọc gần đây' })).toBeVisible();
    await expect(page.locator('.history-status')).toContainText(
      'Đã xóa “Gió qua núi” khỏi lịch sử.',
    );
    await page.screenshot({
      path: testInfo.outputPath(`03-empty-history-${viewport.width}x${viewport.height}.png`),
      fullPage: false,
    });

    if (viewport.width <= 900) {
      const searchLink = page.getByRole('link', { name: 'Tìm tác phẩm trong thư viện' });
      await searchLink.evaluate((element) =>
        element.scrollIntoView({ block: 'center', behavior: 'instant' }),
      );
      const searchLinkBounds = await searchLink.boundingBox();
      const mobileNavigationBounds = await page.locator('.mobile-nav').boundingBox();
      expect(searchLinkBounds).not.toBeNull();
      expect(mobileNavigationBounds).not.toBeNull();
      expect(searchLinkBounds!.y + searchLinkBounds!.height).toBeLessThanOrEqual(
        mobileNavigationBounds!.y,
      );
      await page.screenshot({
        path: testInfo.outputPath(
          `04-empty-history-links-${viewport.width}x${viewport.height}.png`,
        ),
        fullPage: false,
      });
    }

    const dimensions = await page.evaluate(() => ({
      document: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
    }));
    expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
    await expectNoAxeViolations(page);
  });
}
