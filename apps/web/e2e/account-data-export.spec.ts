import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const actorId = '11111111-aaaa-4aaa-8aaa-111111111111';
const exportId = '88f4e518-8587-4b96-b424-d657ef71a660';
const csrfToken = 'A'.repeat(43);
const viewports = [
  { name: 'mobile 360×800', width: 360, height: 800 },
  { name: '200% reflow proxy 180×400', width: 180, height: 400 },
  { name: 'desktop 1280×900', width: 1280, height: 900 },
] as const;

for (const viewport of viewports) {
  test(`account export explains scope and recovers its download at ${viewport.name}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await installSignedInSettings(page);
    let exportStatus: 'queued' | 'ready' | null = null;
    let latestReads = 0;
    await page.route('**/api/v1/me/data-exports/latest', async (route) => {
      latestReads += 1;
      if (exportStatus === null) return json(route, { code: 'EXPORT_NOT_FOUND' }, 404);
      if (exportStatus === 'queued' && latestReads > 2) exportStatus = 'ready';
      return json(route, exportJob(exportStatus));
    });
    await page.route('**/api/v1/auth/csrf', (route) => json(route, { token: csrfToken }));
    await page.route('**/api/v1/me/data-exports', async (route) => {
      expect(route.request().method()).toBe('POST');
      expect(route.request().headers()['x-csrf-token']).toBe(csrfToken);
      expect(route.request().headers()['idempotency-key']).toMatch(/^[\w.-]{16,128}$/);
      expect(route.request().postData()).toBeNull();
      exportStatus = 'queued';
      return json(route, { export: exportJob('queued'), replayed: false }, 202);
    });
    await page.goto('/app/settings');
    const section = page.getByRole('region', { name: 'Tải dữ liệu tài khoản' });
    await expect(section).toBeVisible();
    await expect(section).toContainText('nội dung đầy đủ mà tài khoản này sở hữu');
    await expect(section).toContainText('Nội dung thuộc người khác');
    const acknowledgement = section.getByRole('checkbox', {
      name: 'Tôi hiểu rằng tệp bao gồm toàn bộ nội dung truyện và chương tôi sở hữu.',
    });
    const requestButton = section.getByRole('button', { name: 'Yêu cầu bản export' });
    await expect(acknowledgement).not.toBeChecked();
    await expect(requestButton).toBeDisabled();
    await expectNoAxeViolations(page);

    await acknowledgement.check();
    await expect(requestButton).toBeEnabled();
    await requestButton.click();
    await expect(section).toContainText('Đang chờ xử lý');
    await expect(section.getByRole('button', { name: 'Yêu cầu bản export' })).toBeDisabled();
    await expectNoAxeViolations(page);

    await page.reload();
    const restoredSection = page.getByRole('region', { name: 'Tải dữ liệu tài khoản' });
    await expect(restoredSection).toContainText('Đang chờ xử lý');
    await restoredSection.getByRole('button', { name: 'Kiểm tra trạng thái' }).click();
    await expect(restoredSection).toContainText('Đã sẵn sàng tải xuống');
    await expect(restoredSection.getByText('Hạn tải:')).toBeVisible();
    await expectNoAxeViolations(page);

    const downloadLink = restoredSection.getByRole('link', { name: 'Tải tệp ZIP' });
    await expect(downloadLink).toHaveAttribute(
      'href',
      `/api/v1/me/data-exports/${exportId}/download`,
    );
    await expect(downloadLink).toHaveAttribute('download', 'novel-web-account-data.zip');
    const dimensions = await page.evaluate(() => {
      const viewport = document.documentElement.clientWidth;
      return {
        document: document.documentElement.scrollWidth,
        viewport,
        overflowers: Array.from(document.querySelectorAll<HTMLElement>('*'))
          .map((element) => ({
            selector: `${element.tagName.toLowerCase()}${element.className ? `.${String(element.className).replaceAll(' ', '.')}` : ''}`,
            text: element.innerText?.slice(0, 60),
            right: element.getBoundingClientRect().right,
            width: element.getBoundingClientRect().width,
          }))
          .filter((element) => element.right > viewport + 1)
          .slice(0, 8),
      };
    });
    expect(dimensions.document, JSON.stringify(dimensions.overflowers)).toBeLessThanOrEqual(
      dimensions.viewport,
    );
    await page.screenshot({
      path: testInfo.outputPath(`account-export-${viewport.width}x${viewport.height}.png`),
      fullPage: true,
    });
  });
}

async function installSignedInSettings(page: Page): Promise<void> {
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '10.10.0',
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
        publicCatalogEnabled: false,
      },
    }),
  );
  await page.route('**/api/v1/auth/me', (route) =>
    json(route, {
      user: {
        id: actorId,
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
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Điều khoản' }),
  );
  await page.route('**/api/v1/auth/policies/privacy-notice/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Quyền riêng tư' }),
  );
}

function exportJob(status: 'queued' | 'ready') {
  return {
    id: exportId,
    status,
    requestedAt: '2026-11-18T10:00:00Z',
    startedAt: status === 'ready' ? '2026-11-18T10:00:01Z' : null,
    readyAt: status === 'ready' ? '2026-11-18T10:00:02Z' : null,
    expiresAt: status === 'ready' ? '2026-11-19T10:00:02Z' : null,
    artifactBytes: status === 'ready' ? 1024 : null,
    failureCode: null,
    downloadUrl: status === 'ready' ? `/api/v1/me/data-exports/${exportId}/download` : null,
  };
}

async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}
