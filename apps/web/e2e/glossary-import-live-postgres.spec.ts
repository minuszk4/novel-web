import { expect, test } from './support';
import type { Response } from '@playwright/test';

const baseUrl = 'http://localhost:4300';

test('glossary import saves one real PostgreSQL draft without publishing it', async ({ page }) => {
  const sessionToken = process.env['LIBRARY_LIVE_TEST_SESSION_TOKEN'];
  const glossaryId = process.env['GLOSSARY_LIVE_TEST_GLOSSARY_ID'];
  const screenshotPath = process.env['GLOSSARY_LIVE_TEST_SCREENSHOT'];

  test.skip(
    !sessionToken || !glossaryId || !screenshotPath,
    'run tools/e2e/run-library-postgres.ps1 to seed a disposable glossary and owner session',
  );
  expect(sessionToken, 'expected the disposable PostgreSQL session fixture').toMatch(
    /^[A-Za-z0-9_-]{43}$/,
  );
  expect(glossaryId, 'expected a disposable PostgreSQL glossary fixture').toMatch(
    /^[0-9a-f-]{36}$/i,
  );

  await page.setViewportSize({ width: 1280, height: 900 });
  await page.context().addCookies([
    {
      name: '__Host-novel_session',
      value: sessionToken!,
      domain: 'localhost',
      path: '/',
      httpOnly: true,
      secure: true,
      sameSite: 'Lax',
    },
  ]);

  const apiResponses: Response[] = [];
  const apiRequests: string[] = [];
  page.on('response', (response) => {
    if (new URL(response.url()).pathname.startsWith('/api/v1/')) apiResponses.push(response);
  });
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/v1/glossaries/')) {
      apiRequests.push(`${request.method()} ${url.pathname}`);
    }
  });
  await page.goto(`${baseUrl}/app/glossaries/${glossaryId}/import`);
  await expect(page.getByRole('heading', { name: 'E2E Glossary Import' })).toBeVisible();
  await expect(page.getByText('Bản nháp chưa xuất bản.', { exact: false })).toBeVisible();

  const fileContents = JSON.stringify({
    version: 1,
    terms: [{ sourceTerm: '丹田', targetTerm: 'Đan điền', aliases: ['Dantian'] }],
  });
  await page.locator('input[type="file"]').setInputFiles({
    name: 'dantian.json',
    mimeType: 'application/json',
    buffer: Buffer.from(fileContents, 'utf8'),
  });
  await expect(page.getByText('dantian.json')).toBeVisible();
  expect(apiRequests.some((request) => request.includes('/terms/import-preview'))).toBe(false);

  const previewResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === `/api/v1/glossaries/${glossaryId}/terms/import-preview`,
  );
  await page.getByRole('button', { name: 'Xem trước nhập' }).click();
  const previewResponse = await previewResponsePromise;
  const previewBody = await previewResponse.text();
  expect(previewResponse.status(), previewBody).toBe(200);
  expect(previewResponse.headers()['etag']).toBe('"v4"');
  const preview = JSON.parse(previewBody) as {
    glossaryVersion: number;
    activeRevisionNumber: number;
    baseRevisionNumber: number;
    importedTerms: Array<{ suggestedAction: string; importedTerm: { sourceTerm: string } }>;
  };
  expect(preview).toMatchObject({
    glossaryVersion: 4,
    activeRevisionNumber: 1,
    baseRevisionNumber: 2,
  });
  expect(preview.importedTerms).toHaveLength(1);
  expect(preview.importedTerms[0]).toMatchObject({
    suggestedAction: 'ADD',
    importedTerm: { sourceTerm: '丹田' },
  });
  await expect(page.getByRole('heading', { name: 'Kết quả xem trước' })).toBeFocused();
  await expect(page.getByRole('button', { name: /Thêm mục/ })).toBeVisible();

  const applyResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === `/api/v1/glossaries/${glossaryId}/terms/import-apply`,
  );
  await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
  const applyResponse = await applyResponsePromise;
  expect(applyResponse.status()).toBe(201);
  expect(applyResponse.headers()['etag']).toBe('"v5"');
  const applied = (await applyResponse.json()) as {
    draft: { revisionNumber: number; glossaryVersion: number };
    rows: Array<{ rowNumber: number; action: string }>;
    replayed: boolean;
  };
  expect(applied).toMatchObject({
    draft: { revisionNumber: 3, glossaryVersion: 5 },
    rows: [{ rowNumber: 1, action: 'ADD' }],
    replayed: false,
  });
  await expect(page.getByRole('heading', { name: 'Đã lưu bản nháp 3' })).toBeFocused();
  await expect(page.getByText('Bản đang áp dụng vẫn là 1.')).toBeVisible();

  const ownerActiveExport = await page.request.get(
    `${baseUrl}/api/v1/glossaries/${glossaryId}/export?format=json&revision=active`,
  );
  expect(ownerActiveExport.status()).toBe(200);
  expect(ownerActiveExport.headers()['cache-control']).toContain('no-store');
  expect(ownerActiveExport.headers()['etag']).toBe('"v5"');
  expect(ownerActiveExport.headers()['content-disposition']).toContain('-r1.json');
  const ownerActiveExportBody = JSON.parse(await ownerActiveExport.text()) as {
    version: number;
    terms: Array<{ sourceTerm: string; targetTerm: string | null }>;
  };
  expect(ownerActiveExportBody.version).toBe(1);
  expect(ownerActiveExportBody.terms).toContainEqual(
    expect.objectContaining({ sourceTerm: '金丹', targetTerm: 'Kim Đan' }),
  );

  const ownerLatestExport = await page.request.get(
    `${baseUrl}/api/v1/glossaries/${glossaryId}/export?format=json&revision=latest`,
  );
  expect(ownerLatestExport.status()).toBe(200);
  expect(ownerLatestExport.headers()['cache-control']).toContain('no-store');
  expect(ownerLatestExport.headers()['etag']).toBe('"v5"');
  expect(ownerLatestExport.headers()['content-disposition']).toContain('-r3.json');
  expect(await ownerLatestExport.text()).toContain('丹田');

  const otherSessionToken = process.env['MOMO_COMMERCE_LIVE_TEST_OTHER_SESSION_TOKEN'];
  if (otherSessionToken) {
    expect(otherSessionToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
    await page.context().clearCookies();
    await page.context().addCookies([
      {
        name: '__Host-novel_session',
        value: otherSessionToken,
        domain: 'localhost',
        path: '/',
        httpOnly: true,
        secure: true,
        sameSite: 'Lax',
      },
    ]);

    for (const revision of ['active', 'latest']) {
      const foreignExport = await page.request.get(
        `${baseUrl}/api/v1/glossaries/${glossaryId}/export?format=json&revision=${revision}`,
      );
      expect(foreignExport.status()).toBe(404);
      expect(foreignExport.headers()['cache-control']).toContain('no-store');
      const foreignExportBody = await foreignExport.text();
      expect(foreignExportBody).toContain('NOT_FOUND');
      expect(foreignExportBody).not.toContain('金丹');
      expect(foreignExportBody).not.toContain('丹田');
      expect(foreignExportBody).not.toContain('Kim Đan');
    }

    await page.context().clearCookies();
    await page.context().addCookies([
      {
        name: '__Host-novel_session',
        value: sessionToken!,
        domain: 'localhost',
        path: '/',
        httpOnly: true,
        secure: true,
        sameSite: 'Lax',
      },
    ]);
  }

  const persistedMetadata = await page.evaluate(async (id) => {
    const response = await fetch(`/api/v1/glossaries/${id}`, { cache: 'no-store' });
    return {
      status: response.status,
      etag: response.headers.get('ETag'),
      body: (await response.json()) as {
        version: number;
        activeRevisionNumber: number;
        latestRevisionNumber: number;
      },
    };
  }, glossaryId!);
  expect(persistedMetadata).toMatchObject({
    status: 200,
    etag: '"v5"',
    body: { version: 5, activeRevisionNumber: 1, latestRevisionNumber: 3 },
  });
  expect(apiRequests).toContain(`POST /api/v1/glossaries/${glossaryId}/terms/import-preview`);
  expect(apiRequests).toContain(`POST /api/v1/glossaries/${glossaryId}/terms/import-apply`);
  expect(
    apiResponses
      .filter((response) => new URL(response.url()).pathname.startsWith('/api/v1/glossaries/'))
      .map((response) => response.status())
      .every((status) => status < 400),
  ).toBe(true);
  await page.screenshot({ path: screenshotPath!, fullPage: true });
});

test('glossary editor saves a full latest snapshot as the next PostgreSQL draft', async ({
  page,
}) => {
  const sessionToken = process.env['LIBRARY_LIVE_TEST_SESSION_TOKEN'];
  const glossaryId = process.env['GLOSSARY_LIVE_TEST_GLOSSARY_ID'];
  const screenshotPath = process.env['GLOSSARY_EDITOR_LIVE_TEST_SCREENSHOT'];

  test.skip(
    !sessionToken || !glossaryId || !screenshotPath,
    'run tools/e2e/run-library-postgres.ps1 to seed a disposable glossary and owner session',
  );
  expect(sessionToken, 'expected the disposable PostgreSQL session fixture').toMatch(
    /^[A-Za-z0-9_-]{43}$/,
  );
  expect(glossaryId, 'expected a disposable PostgreSQL glossary fixture').toMatch(
    /^[0-9a-f-]{36}$/i,
  );

  await page.setViewportSize({ width: 1280, height: 900 });
  await page.context().addCookies([
    {
      name: '__Host-novel_session',
      value: sessionToken!,
      domain: 'localhost',
      path: '/',
      httpOnly: true,
      secure: true,
      sameSite: 'Lax',
    },
  ]);

  const apiRequests: string[] = [];
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/v1/glossaries/')) {
      apiRequests.push(`${request.method()} ${url.pathname}`);
    }
  });
  await page.goto(`${baseUrl}/app/glossaries/${glossaryId}/edit`);
  await expect(page.getByRole('heading', { name: 'E2E Glossary Import' })).toBeVisible();
  await expect(page.getByText('Nguồn sửa đổi: 3')).toBeVisible();
  await expect(page.getByText('Bản đang áp dụng: 1')).toBeVisible();

  const target = page.locator('.glossary-editor-term').nth(1).locator('input').nth(1);
  await target.fill('Đan điền khí hải');
  const saveResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === `/api/v1/glossaries/${glossaryId}/revisions`,
  );
  await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
  const saveResponse = await saveResponsePromise;
  const saveBody = await saveResponse.text();
  expect(saveResponse.status(), saveBody).toBe(201);
  expect(saveResponse.headers()['etag']).toBe('"v6"');
  expect(JSON.parse(saveBody)).toMatchObject({
    glossaryId,
    revisionNumber: 4,
    parentRevisionId: expect.any(String),
    glossaryVersion: 6,
  });
  await expect(page.getByRole('heading', { name: 'Đã tạo bản nháp 4' })).toBeVisible();
  await expect(page.getByText('Bản đang áp dụng vẫn là 1')).toBeVisible();

  await page.getByRole('button', { name: 'Tiếp tục biên tập' }).click();
  await expect(page.getByText('Nguồn sửa đổi: 4')).toBeVisible();
  await page.getByLabel(/Văn bản mẫu/).fill('丹田与金丹');
  const previewResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname ===
        `/api/v1/glossaries/${glossaryId}/revisions/${JSON.parse(saveBody).revisionId}/preview`,
  );
  await page.getByRole('button', { name: 'Xem trước bản đã lưu' }).click();
  const previewResponse = await previewResponsePromise;
  const previewBody = await previewResponse.json();
  expect(previewResponse.status()).toBe(200);
  expect(previewResponse.headers()['etag']).toBe('"v6"');
  expect(previewBody).toMatchObject({
    glossaryId,
    proposedRevisionId: JSON.parse(saveBody).revisionId,
    glossaryVersion: 6,
    canPublish: true,
    matchCount: 2,
    matches: expect.arrayContaining([
      expect.objectContaining({ matchedText: '丹田', targetTerm: 'Đan điền khí hải' }),
      expect.objectContaining({ matchedText: '金丹', targetTerm: 'Kim Đan' }),
    ]),
  });
  await expect(page.getByText('2 kết quả khớp')).toBeVisible();
  await expect(page.getByText('Không có lỗi lint chặn xuất bản')).toBeVisible();
  expect(apiRequests).toContain(
    `POST /api/v1/glossaries/${glossaryId}/revisions/${JSON.parse(saveBody).revisionId}/preview`,
  );

  const latest = await page.evaluate(async (id) => {
    const response = await fetch(`/api/v1/glossaries/${id}/latest-revision`, { cache: 'no-store' });
    return {
      status: response.status,
      etag: response.headers.get('ETag'),
      body: (await response.json()) as {
        revisionNumber: number;
        terms: Array<{ id: string; sourceTerm: string; targetTerm: string | null }>;
      },
    };
  }, glossaryId!);
  expect(latest).toMatchObject({
    status: 200,
    etag: '"v6"',
    body: {
      revisionNumber: 4,
      terms: expect.arrayContaining([
        expect.objectContaining({ sourceTerm: '丹田', targetTerm: 'Đan điền khí hải' }),
        expect.objectContaining({ sourceTerm: '金丹', targetTerm: 'Kim Đan' }),
      ]),
    },
  });
  expect(apiRequests).toContain(`GET /api/v1/glossaries/${glossaryId}/latest-revision`);
  expect(apiRequests).toContain(`POST /api/v1/glossaries/${glossaryId}/revisions`);
  expect(
    latest.body.revisionNumber,
    'preview must leave the latest immutable revision unchanged',
  ).toBe(4);
  expect(latest.body.terms).toHaveLength(2);
  await page.screenshot({ path: screenshotPath!, fullPage: true });
});
