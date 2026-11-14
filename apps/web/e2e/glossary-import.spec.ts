import { expect, expectNoAxeViolations, test } from './support';

const glossaryId = '11111111-1111-4111-8111-111111111111';
const activeRevisionId = '22222222-2222-4222-8222-222222222222';
const baseRevisionId = '33333333-3333-4333-8333-333333333333';
const currentTermId = '44444444-4444-4444-8444-444444444444';
const secondTermId = '55555555-5555-4555-8555-555555555555';

test('glossary import previews without upload-on-selection and saves an atomic draft responsively', async ({
  page,
}, testInfo) => {
  const traffic = {
    previewRequests: 0,
    previewIfMatch: null as string | null,
    applyRequests: 0,
    applyIfMatch: null as string | null,
    idempotencyKey: null as string | null,
    resolutions: [] as Array<Record<string, unknown>>,
  };

  await page.route('**/api/v1/system/status', (route) =>
    route.fulfill({
      json: {
        apiVersion: '1.0.0',
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
      },
    }),
  );
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({
      json: {
        user: {
          id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
          displayName: 'Người đọc',
          contactEmail: 'reader@example.test',
        },
        policy: {
          termsAcceptedVersion: 'terms-current',
          privacyNoticeAcknowledgedVersion: 'privacy-current',
          requiredAcceptanceComplete: true,
        },
      },
    }),
  );
  await page.route('**/api/v1/auth/policies/current', (route) =>
    route.fulfill({
      json: {
        terms: {
          version: 'terms-current',
          effectiveAt: '2026-11-01T00:00:00Z',
          documentUrl: '/api/v1/auth/policies/terms-current',
        },
        privacyNotice: {
          version: 'privacy-current',
          effectiveAt: '2026-11-01T00:00:00Z',
          documentUrl: '/api/v1/auth/policies/privacy-current',
        },
      },
    }),
  );
  await page.route('**/api/v1/auth/policies/terms-current', (route) =>
    route.fulfill({ contentType: 'text/plain', body: 'Điều khoản.' }),
  );
  await page.route('**/api/v1/auth/policies/privacy-current', (route) =>
    route.fulfill({ contentType: 'text/plain', body: 'Quyền riêng tư.' }),
  );
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({ json: { token: 'A'.repeat(43) } }),
  );
  await page.route('**/api/v1/glossaries/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === `/api/v1/glossaries/${glossaryId}` && request.method() === 'GET') {
      return route.fulfill({
        headers: { ETag: '"v4"', 'Cache-Control': 'no-store' },
        json: {
          id: glossaryId,
          name: 'Tu luyện',
          scope: 'LIBRARY',
          sourceLanguage: 'zh-Hans',
          targetLanguage: 'vi',
          version: 4,
          activeRevisionId,
          activeRevisionNumber: 1,
          latestRevisionNumber: 2,
          createdAt: '2026-11-14T05:00:00Z',
          canWrite: true,
        },
      });
    }
    if (
      path === `/api/v1/glossaries/${glossaryId}/terms/import-preview` &&
      request.method() === 'POST'
    ) {
      traffic.previewRequests++;
      traffic.previewIfMatch = request.headers()['if-match'] ?? null;
      return route.fulfill({
        headers: { ETag: '"v4"', 'Cache-Control': 'no-store' },
        json: {
          glossaryId,
          glossaryName: 'Tu luyện',
          scope: 'LIBRARY',
          sourceLanguage: 'zh-Hans',
          targetLanguage: 'vi',
          glossaryVersion: 4,
          activeRevisionId,
          activeRevisionNumber: 1,
          baseRevisionId,
          baseRevisionNumber: 2,
          currentTerms: [
            {
              id: currentTermId,
              ordinal: 1,
              sourceTerm: '金丹',
              targetTerm: 'Kim Đan',
              priority: 2,
              casePolicy: 'INSENSITIVE',
              matchMode: 'WHOLE_PHRASE',
              doNotTranslate: false,
              contextNote: 'Cảnh giới đã duyệt',
              aliases: ['Golden Core'],
            },
            {
              id: secondTermId,
              ordinal: 2,
              sourceTerm: 'Golden Core',
              targetTerm: 'Kim Đan',
              priority: 1,
              casePolicy: 'INSENSITIVE',
              matchMode: 'WHOLE_PHRASE',
              doNotTranslate: false,
              contextNote: null,
              aliases: [],
            },
          ],
          importedTerms: [
            {
              rowNumber: 2,
              importedTerm: {
                rowNumber: 2,
                sourceTerm: '丹田',
                targetTerm: 'Đan điền',
                priority: 0,
                casePolicy: 'INSENSITIVE',
                matchMode: 'WHOLE_PHRASE',
                doNotTranslate: false,
                contextNote: null,
                aliases: ['New Alias'],
              },
              suggestedAction: 'ADD',
              existingMatches: [],
              matchesTruncated: false,
            },
            {
              rowNumber: 3,
              importedTerm: {
                rowNumber: 3,
                sourceTerm: '金丹',
                targetTerm: 'Golden Core',
                priority: 0,
                casePolicy: 'INSENSITIVE',
                matchMode: 'WHOLE_PHRASE',
                doNotTranslate: false,
                contextNote: null,
                aliases: ['Kim Dan'],
              },
              suggestedAction: 'UPDATE',
              existingMatches: [{ termId: currentTermId, reason: 'CANONICAL_TERM_MATCH' }],
              matchesTruncated: false,
            },
            {
              rowNumber: 4,
              importedTerm: {
                rowNumber: 4,
                sourceTerm: 'Kim Dan',
                targetTerm: 'Kim Đan',
                priority: 0,
                casePolicy: 'INSENSITIVE',
                matchMode: 'WHOLE_PHRASE',
                doNotTranslate: false,
                contextNote: null,
                aliases: [],
              },
              suggestedAction: 'RESOLVE_CONFLICT',
              existingMatches: [
                { termId: currentTermId, reason: 'PATTERN_COLLISION' },
                { termId: secondTermId, reason: 'PATTERN_COLLISION' },
              ],
              matchesTruncated: false,
            },
            {
              rowNumber: 5,
              importedTerm: {
                rowNumber: 5,
                sourceTerm: 'Core',
                targetTerm: 'Lõi',
                priority: 0,
                casePolicy: 'INSENSITIVE',
                matchMode: 'WHOLE_PHRASE',
                doNotTranslate: false,
                contextNote: null,
                aliases: [],
              },
              suggestedAction: 'RESOLVE_CONFLICT',
              existingMatches: [{ termId: secondTermId, reason: 'PATTERN_COLLISION' }],
              matchesTruncated: true,
            },
          ],
          counts: { imported: 4, add: 1, update: 1, unchanged: 0, requiresResolution: 2 },
          translationImpact: {
            status: 'UNAVAILABLE',
            affectedTranslationUnitCount: null,
            detail: 'Translation units are not implemented yet.',
          },
        },
      });
    }
    if (
      path === `/api/v1/glossaries/${glossaryId}/terms/import-apply` &&
      request.method() === 'POST'
    ) {
      traffic.applyRequests++;
      traffic.applyIfMatch = request.headers()['if-match'] ?? null;
      traffic.idempotencyKey = request.headers()['idempotency-key'] ?? null;
      const body = request.postDataJSON() as { resolutions: Array<Record<string, unknown>> };
      traffic.resolutions = body.resolutions;
      return route.fulfill({
        headers: { ETag: '"v5"', 'Cache-Control': 'no-store' },
        json: {
          draft: {
            glossaryId,
            revisionId: '66666666-6666-4666-8666-666666666666',
            revisionNumber: 3,
            parentRevisionId: baseRevisionId,
            glossaryVersion: 5,
          },
          rows: [
            { rowNumber: 2, action: 'ADD', termId: '77777777-7777-4777-8777-777777777777' },
            { rowNumber: 3, action: 'MERGE', termId: currentTermId },
            { rowNumber: 4, action: 'SKIP', termId: null },
            { rowNumber: 5, action: 'SKIP', termId: null },
          ],
          skippedRows: 2,
          replayed: false,
        },
      });
    }
    return route.fulfill({ status: 404, json: { code: 'NOT_FOUND' } });
  });

  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto(`/app/glossaries/${glossaryId}/import`);
  await expect(page.getByRole('heading', { name: 'Tu luyện' })).toBeVisible();
  await expect(page.getByText('Bản nháp chưa xuất bản.', { exact: false })).toBeVisible();

  await page.locator('input[type="file"]').setInputFiles({
    name: 'terms.json',
    mimeType: 'application/json',
    buffer: Buffer.from('{"version":1,"terms":[]}'),
  });
  await expect(page.getByText('terms.json')).toBeVisible();
  expect(traffic.previewRequests).toBe(0);
  await expectNoAxeViolations(page);

  await page.getByRole('button', { name: 'Xem trước nhập' }).click();
  await expect(page.getByRole('heading', { name: 'Kết quả xem trước' })).toBeFocused();
  await expect(page.getByText('Chưa thể tính ảnh hưởng đến bản dịch')).toBeVisible();
  expect(traffic.previewRequests).toBe(1);
  expect(traffic.previewIfMatch).toBe('"v4"');
  await expect(page.getByText(currentTermId)).toHaveCount(0);
  await expectNoAxeViolations(page);

  const measurements: Array<Record<string, unknown>> = [];
  for (const viewport of [
    { name: 'mobile-360x800', width: 360, height: 800 },
    { name: 'tablet-768x1024', width: 768, height: 1024 },
    { name: 'desktop-1280x900', width: 1280, height: 900 },
    { name: 'effective-200-percent', width: 640, height: 900 },
  ]) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    const dimensions = await page.evaluate(() => ({
      viewport: document.documentElement.clientWidth,
      document: document.documentElement.scrollWidth,
      resultCards: document.querySelectorAll('.glossary-import-row').length,
      saveVisible: !!document.querySelector('.glossary-import-save-bar button'),
    }));
    expect(
      dimensions.document,
      `${viewport.name} must not overflow horizontally`,
    ).toBeLessThanOrEqual(dimensions.viewport);
    measurements.push({ ...viewport, ...dimensions });
    if (viewport.width === 360) {
      await expect(page.getByRole('heading', { name: 'Kết quả xem trước' })).toBeVisible();
      await page.screenshot({
        path: testInfo.outputPath('glossary-import-preview-360x800.png'),
        fullPage: true,
      });
    }
  }

  await page.getByRole('button', { name: 'Gộp bí danh — “金丹”' }).focus();
  await page.keyboard.press('Enter');
  await expect(
    page.locator('#glossary-import-row-3 button[aria-pressed="true"]'),
  ).toHaveAccessibleName('Gộp bí danh — “金丹”');
  await page
    .locator('#glossary-import-row-4')
    .getByRole('button', { name: 'Bỏ qua dòng 4' })
    .click();
  const truncatedRow = page.locator('#glossary-import-row-5');
  await expect(truncatedRow.getByRole('button')).toHaveCount(1);
  await truncatedRow.getByRole('button', { name: 'Bỏ qua dòng 5' }).click();
  await expectNoAxeViolations(page);

  await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
  await expect(page.getByRole('heading', { name: 'Đã lưu bản nháp 3' })).toBeFocused();
  expect(traffic.applyRequests).toBe(1);
  expect(traffic.applyIfMatch).toBe('"v4"');
  expect(traffic.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/i);
  expect(traffic.resolutions.find((row) => row['rowNumber'] === 3)).toMatchObject({
    action: 'MERGE',
  });
  expect(traffic.resolutions.find((row) => row['rowNumber'] === 4)).toMatchObject({
    action: 'SKIP',
  });
  await expect(page.getByText('Bản đang áp dụng vẫn là 1.')).toBeVisible();

  await page.setViewportSize({ width: 360, height: 800 });
  await page.screenshot({
    path: testInfo.outputPath('glossary-import-saved-360x800.png'),
    fullPage: true,
  });
  expect(measurements).toHaveLength(4);
  await testInfo.attach('glossary-import-ui-qa.json', {
    body: Buffer.from(
      JSON.stringify(
        {
          coverage:
            'Chromium browser UI with mocked API routes; PostgreSQL behavior is not inferred from this run.',
          measurements,
          previewRequests: traffic.previewRequests,
          previewIfMatch: traffic.previewIfMatch,
          applyRequests: traffic.applyRequests,
          applyIfMatch: traffic.applyIfMatch,
          hasIdempotencyKey: !!traffic.idempotencyKey,
          stableTermIdsHiddenInRenderedText: true,
          axeChecks: 3,
        },
        null,
        2,
      ),
    ),
    contentType: 'application/json',
  });
});
