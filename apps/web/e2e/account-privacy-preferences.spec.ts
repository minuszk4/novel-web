import { expect, expectNoAxeViolations, test } from './support';

const actorId = '85d9d3bb-2880-4762-a6a8-62e5abf477a9';
const csrfToken = 'A'.repeat(43);
const viewports = [
  { name: 'mobile 360×800', width: 360, height: 800 },
  { name: 'short landscape 640×360', width: 640, height: 360 },
  { name: 'desktop 1280×900', width: 1280, height: 900 },
] as const;

for (const viewport of viewports) {
  test(`signed-in optional privacy settings persist and reflow at ${viewport.name}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });

    let saved = {
      analyticsEnabled: false,
      contentProviderSharingEnabled: false,
      version: 0,
      updatedAt: null as string | null,
    };
    let observedWrite: { body: unknown; csrf: string | null; etag: string | null } | null = null;

    await page.route('**/api/v1/auth/me', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          user: { id: actorId, displayName: 'Độc giả kiểm thử', contactEmail: null },
          policy: {
            termsAcceptedVersion: null,
            privacyNoticeAcknowledgedVersion: null,
            requiredAcceptanceComplete: false,
          },
        }),
      }),
    );
    await page.route('**/api/v1/auth/policies/current', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
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
      }),
    );
    await page.route('**/api/v1/auth/policies/terms/**', (route) =>
      route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Điều khoản' }),
    );
    await page.route('**/api/v1/auth/policies/privacy-notice/**', (route) =>
      route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Quyền riêng tư' }),
    );
    await page.route('**/api/v1/auth/csrf', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ token: csrfToken }),
      }),
    );
    await page.route('**/api/v1/me/privacy-preferences', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          headers: { ETag: `"privacy-preferences-v${saved.version}"`, 'Cache-Control': 'no-store' },
          body: JSON.stringify(saved),
        });
        return;
      }
      observedWrite = {
        body: route.request().postDataJSON(),
        csrf: route.request().headers()['x-csrf-token'] ?? null,
        etag: route.request().headers()['if-match'] ?? null,
      };
      saved = {
        analyticsEnabled: true,
        contentProviderSharingEnabled: false,
        version: 1,
        updatedAt: '2026-11-25T00:00:00Z',
      };
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { ETag: '"privacy-preferences-v1"', 'Cache-Control': 'no-store' },
        body: JSON.stringify(saved),
      });
    });
    await page.route('**/api/v1/me/subscriptions?**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { 'Cache-Control': 'no-store' },
        body: JSON.stringify({ items: [], nextCursor: null }),
      }),
    );
    await page.route('**/api/v1/me/data-exports/latest', (route) =>
      route.fulfill({
        status: 404,
        contentType: 'application/problem+json',
        body: '{"code":"EXPORT_NOT_FOUND"}',
      }),
    );

    await page.goto('/app/settings');
    const privacy = page.locator('#privacy-settings');
    await expect(privacy.getByRole('heading', { name: 'Lựa chọn tùy chọn' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Đọc và xác nhận chính sách' })).toHaveAttribute(
      'href',
      '/app/setup',
    );
    await expect(page.locator('app-work-subscription-settings')).toHaveCount(0);
    await expect(page.locator('app-account-data-export-settings')).toHaveCount(0);
    const analytics = privacy.getByRole('checkbox', { name: /Phân tích sử dụng/ });
    const provider = privacy.getByRole('checkbox', {
      name: /Chia sẻ nội dung với dịch vụ bên ngoài/,
    });
    await expect(analytics).not.toBeChecked();
    await expect(provider).not.toBeChecked();
    await expect(privacy).toContainText('chưa thu thập dữ liệu analytics');
    await expect(privacy).toContainText('chưa kết nối provider dịch nội dung nào');
    await page.screenshot({
      path: testInfo.outputPath(`privacy-pending-${viewport.width}x${viewport.height}.png`),
      fullPage: true,
    });

    await analytics.focus();
    await analytics.press('Space');
    await expect(analytics).toBeChecked();
    await expect(privacy).toContainText('Lựa chọn quyền riêng tư đã được lưu.');
    expect(observedWrite).toEqual({
      body: { analyticsEnabled: true, contentProviderSharingEnabled: false },
      csrf: csrfToken,
      etag: '"privacy-preferences-v0"',
    });
    await expect(provider).not.toBeChecked();
    await page.screenshot({
      path: testInfo.outputPath(`privacy-saved-${viewport.width}x${viewport.height}.png`),
      fullPage: true,
    });

    await page.reload();
    await expect(privacy.getByRole('checkbox', { name: /Phân tích sử dụng/ })).toBeChecked();
    await expect(
      privacy.getByRole('checkbox', { name: /Chia sẻ nội dung với dịch vụ bên ngoài/ }),
    ).not.toBeChecked();

    const dimensions = await page.evaluate(() => ({
      document: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
    }));
    expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
    await expectNoAxeViolations(page);
  });
}
