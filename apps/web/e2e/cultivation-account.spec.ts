import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const csrfToken = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const catalog = [
  { key: 'mortal', group: 'Nhập môn', name: 'Phàm nhân', thresholdXp: 0, sortOrder: 0 },
  ...Array.from({ length: 9 }, (_, index) => ({
    key: `qi-${index + 1}`,
    group: 'Luyện Khí',
    name: `Luyện Khí tầng ${index + 1}`,
    thresholdXp: (index + 1) * 100,
    sortOrder: index + 1,
  })),
  ...[
    ['foundation', 'Tứ đại cảnh', 'Trúc Cơ', 1_000],
    ['golden-core', 'Tứ đại cảnh', 'Kim Đan', 2_500],
    ['nascent-soul', 'Tứ đại cảnh', 'Nguyên Anh', 5_000],
    ['spirit', 'Thượng giới', 'Hóa Thần', 9_000],
    ['void', 'Thượng giới', 'Luyện Hư', 15_000],
    ['integration', 'Thượng giới', 'Hợp Thể', 23_000],
    ['mahayana', 'Thượng giới', 'Đại Thừa', 33_000],
    ['tribulation', 'Thượng giới', 'Độ Kiếp', 45_000],
  ].map(([key, group, name, thresholdXp], index) => ({
    key: String(key),
    group: String(group),
    name: String(name),
    thresholdXp: Number(thresholdXp),
    sortOrder: index + 10,
  })),
];

const initialProfile = {
  enabled: true,
  profileVersion: 1,
  totalXp: 620,
  todayXpEarned: 42,
  currentRank: catalog[6],
  nextRank: catalog[7],
  xpToNextRank: 80,
  progressRatio: 0.2,
  rankCatalogVersion: 'cultivation-v1',
  policyVersion: 'xp-v1',
  locale: 'vi-VN',
  xpPerActiveMinute: 1,
  dailyXpCap: 120,
  nextDailyResetAt: '2026-11-14T00:00:00Z',
  rankCatalog: catalog,
};

const activityPage = {
  items: [
    { activityDate: '2026-11-13', xpEarned: 42 },
    { activityDate: '2026-11-12', xpEarned: 61 },
  ],
  nextCursor: null,
};

const viewports = [
  { name: 'mobile 360×800', evidenceName: 'mobile-360x800', width: 360, height: 800 },
  { name: 'landscape 640×360', evidenceName: 'landscape-640x360', width: 640, height: 360 },
  { name: 'tablet 768×1024', evidenceName: 'tablet-768x1024', width: 768, height: 1024 },
  { name: 'desktop 1280×900', evidenceName: 'desktop-1280x900', width: 1280, height: 900 },
  {
    name: '200% reflow proxy 180×400',
    evidenceName: 'reflow-180x400-proxy',
    width: 180,
    height: 400,
  },
] as const;

async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

async function installAcceptedAccount(page: Page): Promise<void> {
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '6.7.0',
      environment: 'test',
      integrations: {
        googleOidcEnabled: false,
        vnpayTestEnabled: false,
        vnpayEnvironment: 'disabled',
      },
      features: {
        importProcessingEnabled: false,
        cultivationEnabled: true,
        commerceSandboxEnabled: false,
        translationFakePreviewEnabled: false,
      },
    }),
  );
  await page.route('**/api/v1/auth/me', (route) =>
    json(route, {
      user: {
        id: 'cultivation-reader-1',
        displayName: 'Độc giả kiểm thử',
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
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Điều khoản kiểm thử' }),
  );
  await page.route('**/api/v1/auth/policies/privacy-notice/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Quyền riêng tư kiểm thử' }),
  );
}

for (const viewport of viewports) {
  test(`account cultivation renders private server state at ${viewport.name}`, async ({ page }) => {
    await installAcceptedAccount(page);
    await page.route('**/api/v1/me/cultivation/activity**', (route) => json(route, activityPage));
    await page.route('**/api/v1/me/cultivation', (route) => json(route, initialProfile));
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto('/app/cultivation');

    await expect(page.getByRole('heading', { name: 'Hành trình tu luyện đang bật' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Luyện Khí tầng 6' })).toBeVisible();
    await expect(page.getByText('620 XP', { exact: true })).toBeVisible();
    await expect(page.getByText('42 / 120 XP · giới hạn UTC')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'XP theo ngày' })).toBeVisible();
    await expect(page.getByText('61 XP', { exact: true })).toBeVisible();
    await expect(page.getByText('Số liệu mẫu để minh họa giao diện.')).toHaveCount(0);
    await expect(page.getByText('Chưa có XP được ghi nhận')).toHaveCount(0);
    const activityText = await page.locator('.cultivation-activity-list').innerText();
    expect(activityText).not.toContain('Sơn Hà');
    expect(activityText).not.toContain('Chương');

    const dimensions = await page.evaluate(() => ({
      document: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
      heading: (() => {
        const eyebrow = document.querySelector('.cultivation-heading .eyebrow');
        const badge = document.querySelector('.cultivation-heading .private-tag');
        return eyebrow && badge
          ? {
              eyebrowBottom: Math.ceil(eyebrow.getBoundingClientRect().bottom),
              badgeTop: Math.ceil(badge.getBoundingClientRect().top),
            }
          : null;
      })(),
    }));
    expect(dimensions.document, JSON.stringify(dimensions)).toBeLessThanOrEqual(
      dimensions.viewport,
    );
    if (viewport.width <= 180) {
      expect(dimensions.heading?.badgeTop).toBeGreaterThanOrEqual(
        dimensions.heading?.eyebrowBottom ?? 0,
      );
    }
    await expectNoAxeViolations(page);
    await page.screenshot({
      path: `../../.ai/evidence/cultivation-account-ux-2026-11-14/${viewport.evidenceName}.png`,
    });
  });
}

test('account cultivation pause is CSRF protected and reflects the saved server response', async ({
  page,
}) => {
  await installAcceptedAccount(page);
  let savedProfile = { ...initialProfile };
  await page.route('**/api/v1/auth/csrf', (route) => json(route, { token: csrfToken }));
  await page.route('**/api/v1/me/cultivation/activity**', (route) => json(route, activityPage));
  await page.route('**/api/v1/me/cultivation/settings', async (route) => {
    expect(route.request().headers()['x-csrf-token']).toBe(csrfToken);
    expect(route.request().postDataJSON()).toEqual({ enabled: false });
    savedProfile = { ...savedProfile, enabled: false, profileVersion: 2 };
    await json(route, savedProfile);
  });
  await page.route('**/api/v1/me/cultivation', (route) => json(route, initialProfile));
  await page.goto('/app/cultivation');

  await page.getByRole('button', { name: 'Tạm dừng tích lũy' }).click();
  await expect(page.getByRole('heading', { name: 'Hành trình đang tạm dừng' })).toBeVisible();
  await expect(page.getByText('XP đã đạt và lịch sử theo ngày vẫn được giữ.')).toBeVisible();
  await expectNoAxeViolations(page);
  await page.screenshot({
    path: '../../.ai/evidence/cultivation-account-ux-2026-11-14/paused-mobile-360x800.png',
  });
});

test('cultivation feature off fails closed without substituting demo progress', async ({
  page,
}) => {
  await installAcceptedAccount(page);
  await page.route('**/api/v1/me/cultivation', (route) =>
    json(route, { code: 'CULTIVATION_NOT_ENABLED' }, 503),
  );
  await page.goto('/app/cultivation');

  await expect(page.getByText('Tính năng tu luyện chưa được bật trên máy chủ.')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Luyện Khí tầng 6' })).toHaveCount(0);
  await expect(page.getByText('Số liệu mẫu để minh họa giao diện.')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Thử tải lại' })).toBeVisible();
  await expectNoAxeViolations(page);
  await page.screenshot({
    path: '../../.ai/evidence/cultivation-account-ux-2026-11-14/feature-disabled-mobile-360x800.png',
  });
});

test('account cultivation announces loading and then renders the server profile', async ({
  page,
}) => {
  await installAcceptedAccount(page);
  await page.route('**/api/v1/me/cultivation/activity**', (route) => json(route, activityPage));
  await page.route('**/api/v1/me/cultivation', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 400));
    await json(route, initialProfile);
  });
  await page.goto('/app/cultivation');

  await expect(page.locator('.library-status-message[role="status"]')).toContainText(
    'Đang tải tiến trình tu luyện',
  );
  await expect(page.getByRole('heading', { name: 'Luyện Khí tầng 6' })).toBeVisible();
});

test('account cultivation profile error has a retry that reloads server state', async ({
  page,
}) => {
  await installAcceptedAccount(page);
  await page.route('**/api/v1/me/cultivation/activity**', (route) => json(route, activityPage));
  let profileReads = 0;
  await page.route('**/api/v1/me/cultivation', async (route) => {
    profileReads += 1;
    if (profileReads === 1) return json(route, { code: 'TEMPORARY_FAILURE' }, 502);
    return json(route, initialProfile);
  });
  await page.goto('/app/cultivation');

  await expect(page.getByRole('alert')).toContainText('Chưa tải được tiến trình tu luyện');
  await page.getByRole('button', { name: 'Thử tải lại' }).click();
  await expect(page.getByRole('heading', { name: 'Luyện Khí tầng 6' })).toBeVisible();
  expect(profileReads).toBe(2);
});

test('account activity error retries without replacing the loaded profile', async ({ page }) => {
  await installAcceptedAccount(page);
  let activityReads = 0;
  await page.route('**/api/v1/me/cultivation/activity**', async (route) => {
    activityReads += 1;
    if (activityReads === 1) return json(route, { code: 'TEMPORARY_FAILURE' }, 503);
    return json(route, activityPage);
  });
  await page.route('**/api/v1/me/cultivation', (route) => json(route, initialProfile));
  await page.goto('/app/cultivation');

  await expect(page.getByRole('heading', { name: 'Luyện Khí tầng 6' })).toBeVisible();
  await expect(page.getByRole('alert')).toContainText('Chưa tải được lịch sử theo ngày');
  await page.getByRole('button', { name: 'Thử lại' }).click();
  await expect(page.getByText('61 XP', { exact: true })).toBeVisible();
  expect(activityReads).toBe(2);
});

test('account activity supports signed-cursor pagination', async ({ page }) => {
  await installAcceptedAccount(page);
  await page.route('**/api/v1/me/cultivation', (route) => json(route, initialProfile));
  let activityReads = 0;
  await page.route('**/api/v1/me/cultivation/activity**', async (route) => {
    activityReads += 1;
    const cursor = new URL(route.request().url()).searchParams.get('cursor');
    if (activityReads === 1) {
      expect(cursor).toBeNull();
      return json(route, {
        items: [{ activityDate: '2026-11-13', xpEarned: 42 }],
        nextCursor: 'older-signed-cursor',
      });
    }
    expect(cursor).toBe('older-signed-cursor');
    return json(route, { items: [{ activityDate: '2026-11-10', xpEarned: 8 }], nextCursor: null });
  });
  await page.goto('/app/cultivation');

  await expect(page.getByText('42 XP', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Xem ngày cũ hơn' }).click();
  await expect(page.getByText('8 XP', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Xem ngày cũ hơn' })).toHaveCount(0);
  expect(activityReads).toBe(2);
  await expectNoAxeViolations(page);
});

test('account activity shows an explicit empty state when the server has no daily XP', async ({
  page,
}) => {
  await installAcceptedAccount(page);
  await page.route('**/api/v1/me/cultivation/activity**', (route) =>
    json(route, { items: [], nextCursor: null }),
  );
  await page.route('**/api/v1/me/cultivation', (route) =>
    json(route, {
      ...initialProfile,
      totalXp: 0,
      todayXpEarned: 0,
      currentRank: catalog[0],
      nextRank: catalog[1],
      xpToNextRank: 100,
      progressRatio: 0,
    }),
  );
  await page.goto('/app/cultivation');

  await expect(page.getByText('Chưa có XP được ghi nhận')).toBeVisible();
  await expect(page.getByText(/Khi bạn đọc bằng Reader/)).toBeVisible();
  await expect(page.locator('.cultivation-activity-list')).toHaveCount(0);
  await expectNoAxeViolations(page);
});
