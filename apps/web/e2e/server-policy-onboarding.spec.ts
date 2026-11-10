import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const firstTimeUser = {
  user: { id: 'user-1', displayName: 'Độc giả', contactEmail: 'reader@example.test', avatarUrl: null, status: 'ACTIVE' },
  identityProviders: ['google'],
  policy: { termsAcceptedVersion: null, privacyNoticeAcknowledgedVersion: null, requiredAcceptanceComplete: false },
  roles: [],
  features: { privateLibraryEnabled: false, importEnabled: false, readerEnabled: false, commerceEnabled: false, cultivationEnabled: false },
};

function policies(termsVersion: string, privacyVersion: string) {
  return {
    terms: {
      version: termsVersion,
      effectiveAt: '2026-11-01T00:00:00Z',
      documentUrl: `/api/v1/auth/policies/terms/${termsVersion}`,
    },
    privacyNotice: {
      version: privacyVersion,
      effectiveAt: '2026-11-01T00:00:00Z',
      documentUrl: `/api/v1/auth/policies/privacy-notice/${privacyVersion}`,
    },
  };
}

async function installServerPolicyRoutes(
  page: Page,
  getVersions: () => { terms: string; privacy: string },
): Promise<void> {
  await page.route('**/api/v1/auth/me', (route) => json(route, firstTimeUser));
  await page.route('**/api/v1/auth/policies/current', (route) => {
    const versions = getVersions();
    return json(route, policies(versions.terms, versions.privacy));
  });
  await page.route('**/api/v1/auth/policies/terms/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/markdown; charset=UTF-8', body: '# Điều khoản hiện hành\n\nĐọc và sử dụng dịch vụ an toàn.' }),
  );
  await page.route('**/api/v1/auth/policies/privacy-notice/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/markdown; charset=UTF-8', body: '# Quyền riêng tư hiện hành\n\nDữ liệu được xử lý minh bạch.' }),
  );
  await page.route('**/api/v1/auth/csrf', (route) => json(route, { token: 'session-bound-csrf' }));
}

async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

test('first server sign-in requires both current policy decisions before Home', async ({ page }) => {
  await installServerPolicyRoutes(page, () => ({ terms: 'terms-1', privacy: 'privacy-1' }));
  let postedPayload: Record<string, unknown> | undefined;
  let postedCsrf: string | undefined;
  await page.route('**/api/v1/auth/policies/accept', async (route) => {
    postedPayload = route.request().postDataJSON() as Record<string, unknown>;
    postedCsrf = route.request().headers()['x-csrf-token'];
    await json(route, {
      termsAcceptedVersion: 'terms-1',
      termsAcceptedAt: '2026-11-10T10:00:00Z',
      privacyNoticeAcknowledgedVersion: 'privacy-1',
      privacyNoticeAcknowledgedAt: '2026-11-10T10:00:00Z',
    });
  });

  await page.goto('/app/home');
  await expect(page).toHaveURL(/\/app\/setup\?returnUrl=%2Fapp%2Fhome$/);
  await page.locator('.policy-document').nth(0).locator('summary').click();
  await page.locator('.policy-document').nth(1).locator('summary').click();
  await expect(page.locator('.policy-document').nth(0).locator('pre')).toContainText('Điều khoản hiện hành');
  await expect(page.locator('.policy-document').nth(1).locator('pre')).toContainText('Quyền riêng tư hiện hành');
  await expectNoAxeViolations(page);
  const continueButton = page.getByRole('button', { name: /Xác nhận và vào không gian đọc/ });
  await expect(continueButton).toBeDisabled();

  await page.locator('.server-consent-list .consent-row').nth(0).click();
  await expect(continueButton).toBeDisabled();
  await page.locator('.server-consent-list .consent-row').nth(1).click();
  await expect(continueButton).toBeEnabled();
  await continueButton.click();

  await expect(page).toHaveURL(/\/app\/home$/);
  expect(postedPayload).toEqual({
    termsVersion: 'terms-1',
    privacyNoticeVersion: 'privacy-1',
    termsAccepted: true,
    privacyNoticeAcknowledged: true,
  });
  expect(postedCsrf).toBe('session-bound-csrf');
});

test('a policy version change during confirmation clears old decisions and requires review again', async ({ page }) => {
  let currentVersions = { terms: 'terms-2', privacy: 'privacy-2' };
  await installServerPolicyRoutes(page, () => currentVersions);
  let submissions = 0;
  const submittedVersions: string[] = [];
  await page.route('**/api/v1/auth/policies/accept', async (route) => {
    submissions += 1;
    const body = route.request().postDataJSON() as { termsVersion: string; privacyNoticeVersion: string };
    submittedVersions.push(`${body.termsVersion}/${body.privacyNoticeVersion}`);
    if (submissions === 1) {
      currentVersions = { terms: 'terms-3', privacy: 'privacy-3' };
      await json(route, { code: 'POLICY_VERSION_STALE', detail: 'Policy changed' }, 409);
      return;
    }
    await json(route, {
      termsAcceptedVersion: 'terms-3',
      termsAcceptedAt: '2026-11-10T10:01:00Z',
      privacyNoticeAcknowledgedVersion: 'privacy-3',
      privacyNoticeAcknowledgedAt: '2026-11-10T10:01:00Z',
    });
  });

  await page.goto('/app/home');
  await expect(page).toHaveURL(/\/app\/setup/);
  await expect(page.locator('.policy-document').nth(0).locator('summary')).toContainText('Phiên bản terms-2');
  await page.locator('.server-consent-list .consent-row').nth(0).click();
  await page.locator('.server-consent-list .consent-row').nth(1).click();
  await page.getByRole('button', { name: /Xác nhận và vào không gian đọc/ }).click();

  await expect(page.locator('.policy-inline-error')).toContainText('phiên bản chính sách mới');
  await expect(page.locator('.policy-document').nth(0).locator('summary')).toContainText('Phiên bản terms-3');
  await expect(page.getByRole('checkbox', { name: /Điều khoản sử dụng hiện hành/ })).not.toBeChecked();
  await expect(page.getByRole('checkbox', { name: /Thông báo quyền riêng tư hiện hành/ })).not.toBeChecked();
  await expectNoAxeViolations(page);
  const continueButton = page.getByRole('button', { name: /Xác nhận và vào không gian đọc/ });
  await expect(continueButton).toBeDisabled();

  await page.locator('.server-consent-list .consent-row').nth(0).click();
  await page.locator('.server-consent-list .consent-row').nth(1).click();
  await continueButton.click();

  await expect(page).toHaveURL(/\/app\/home$/);
  expect(submittedVersions).toEqual(['terms-2/privacy-2', 'terms-3/privacy-3']);
});

test('an authenticated session with no approved policy catalog remains blocked', async ({ page }) => {
  await page.route('**/api/v1/auth/me', (route) => json(route, firstTimeUser));
  await page.route('**/api/v1/auth/policies/current', (route) =>
    json(route, { code: 'POLICY_NOT_CONFIGURED', detail: 'No approved policy catalog' }, 503),
  );

  await page.goto('/app/import');

  await expect(page).toHaveURL(/\/app\/setup\?returnUrl=%2Fapp%2Fimport$/);
  await expect(page.getByRole('alert')).toContainText('chưa có trên máy chủ');
  await expect(page.getByRole('button', { name: /Vào không gian đọc/ })).toBeDisabled();
});
