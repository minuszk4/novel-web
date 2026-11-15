import { expect, expectNoAxeViolations, test } from './support';

const baseUrl = 'http://localhost:4300';
const newChapterTitle = 'Live E2E Blank Outline Chapter';

test('private chapter outline reads and creates metadata through the real API and PostgreSQL', async ({
  page,
}) => {
  const sessionToken = process.env['LIBRARY_LIVE_TEST_SESSION_TOKEN'];
  const workId = process.env['AUTHORING_OUTLINE_LIVE_TEST_WORK_ID'];
  const screenshotPath = process.env['AUTHORING_OUTLINE_LIVE_TEST_SCREENSHOT'];
  const auditScreenshot = (state: string): string =>
    screenshotPath!.replace(/authoring-outline-live\.png$/, `authoring-audit-${state}.png`);

  test.skip(
    !sessionToken || !workId || !screenshotPath,
    'run tools/e2e/run-library-postgres.ps1 -IncludeAuthoringOutline to supply the disposable authored-work fixture',
  );
  expect(sessionToken, 'run with the disposable PostgreSQL fixture harness').toMatch(
    /^[A-Za-z0-9_-]{43}$/,
  );
  expect(workId).toMatch(/^[0-9a-f-]{36}$/i);

  await page.setViewportSize({ width: 360, height: 800 });
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

  const manuscriptListResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/authoring/manuscripts',
  );
  await page.goto(`${baseUrl}/app/authoring`);
  const manuscriptListResponse = await manuscriptListResponsePromise;
  expect(manuscriptListResponse.status()).toBe(200);
  expect(manuscriptListResponse.headers()['cache-control']).toContain('no-store');
  const manuscriptListText = await manuscriptListResponse.text();
  const manuscripts = JSON.parse(manuscriptListText) as {
    items: Array<{
      id: string;
      title: string;
      visibility: string;
      publicationState: string;
      chapterCount: number;
    }>;
    nextCursor: string | null;
  };
  expect(manuscripts.items).toHaveLength(2);
  expect(manuscripts.items.find((item) => item.id === workId)).toMatchObject({
    id: workId,
    title: 'Live E2E Novel Two',
    visibility: 'private',
    publicationState: 'draft',
    chapterCount: 26,
  });
  expect(manuscriptListText).not.toMatch(
    /"(?:ownerUserId|sourceHash|normalizedContent|sourceText|segments|content)"\s*:/i,
  );
  await expect(page.getByRole('heading', { name: 'Tác phẩm của tôi' })).toBeVisible();
  await expect(page.getByText('Riêng tư mặc định')).toBeVisible();
  const listWidths = await page.evaluate(() => ({
    client: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth,
  }));
  expect(listWidths.scroll).toBeLessThanOrEqual(listWidths.client);
  await expectNoAxeViolations(page);
  await page.screenshot({
    path: screenshotPath!.replace(/authoring-outline-live\.png$/, 'authoring-manuscripts-live.png'),
    fullPage: true,
  });
  for (const viewport of [
    { width: 180, height: 400, name: 'effective-200-180x400' },
    { width: 640, height: 450 },
    { width: 1280, height: 900 },
  ]) {
    await page.setViewportSize(viewport);
    const responsiveWidths = await page.evaluate(() => ({
      client: document.documentElement.clientWidth,
      scroll: document.documentElement.scrollWidth,
    }));
    expect(responsiveWidths.scroll).toBeLessThanOrEqual(responsiveWidths.client);
    await expectNoAxeViolations(page);
    if (viewport.width === 180) {
      await page.screenshot({ path: auditScreenshot(`manuscripts-${viewport.name}`) });
    }
  }
  await page.setViewportSize({ width: 360, height: 800 });

  const outlineNavigation = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === `/api/v1/authoring/manuscripts/${workId}/chapters`,
  );
  const detailNavigation = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === `/api/v1/authoring/manuscripts/${workId}`,
  );
  await page.getByRole('link', { name: 'Mở dàn ý chương của Live E2E Novel Two' }).click();
  await Promise.all([outlineNavigation, detailNavigation]);

  const detailResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === `/api/v1/authoring/manuscripts/${workId}`,
  );
  const listResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === `/api/v1/authoring/manuscripts/${workId}/chapters`,
  );
  await page.goto(`${baseUrl}/app/authoring/${workId}/chapters`);
  const [detailResponse, listResponse] = await Promise.all([
    detailResponsePromise,
    listResponsePromise,
  ]);
  expect(detailResponse.status()).toBe(200);
  expect(detailResponse.headers()['cache-control']).toContain('no-store');
  expect(detailResponse.headers().etag).toBe('"manuscript-metadata-1"');
  const detailText = await detailResponse.text();
  expect(JSON.parse(detailText)).toMatchObject({
    id: workId,
    title: 'Live E2E Novel Two',
    author: 'E2E Author',
    language: 'en',
    visibility: 'private',
    publicationState: 'draft',
    chapterCount: 26,
    metadataVersion: 1,
  });
  expect(detailText).not.toMatch(
    /"(?:ownerUserId|sourceHash|normalizedContent|sourceText|segments|content)"\s*:/i,
  );
  expect(listResponse.status()).toBe(200);
  const listText = await listResponse.text();
  const firstPage = JSON.parse(listText) as {
    items: Array<{ id: string; ordinal: number; hasSavedDraftContent: boolean }>;
    nextCursor: string | null;
  };
  expect(firstPage.items).toHaveLength(25);
  expect(firstPage.items[0].ordinal).toBe(1);
  expect(firstPage.items.every((item) => item.hasSavedDraftContent)).toBe(false);
  expect(firstPage.items[24].ordinal).toBe(25);
  expect(firstPage.nextCursor).toMatch(/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/);
  expect(listResponse.headers()['cache-control']).toContain('no-store');
  expect(listText).not.toMatch(/"(?:sourceText|normalizedContent|segments|content)"\s*:/i);
  await expect(page.getByRole('heading', { name: 'Live E2E Novel Two' })).toBeVisible();
  await expect(page.getByText('E2E Author · Ngôn ngữ en · 26 chương')).toBeVisible();
  await expect(page.getByText('Live Translation Chapter', { exact: true })).toBeVisible();
  await expect(page.getByText('Live Outline Seed 25', { exact: true })).toBeVisible();
  await expect(page.getByText('Có nội dung đọc được', { exact: true })).toBeVisible();
  await expect(page.getByText('Chỉ bạn có thể xem mục lục này')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Viết chương' })).toHaveCount(24);
  await expect(
    page.getByRole('listitem').filter({ hasText: 'Live Translation Chapter' }).getByRole('link'),
  ).toHaveCount(0);
  await expectNoAxeViolations(page);
  await page.screenshot({ path: auditScreenshot('outline-360x800') });

  const cursorRequestPromise = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return (
      response.request().method() === 'GET' &&
      url.pathname === `/api/v1/authoring/manuscripts/${workId}/chapters` &&
      url.searchParams.has('cursor')
    );
  });
  await page.getByRole('button', { name: 'Tải thêm chương' }).click();
  const cursorResponse = await cursorRequestPromise;
  expect(cursorResponse.status()).toBe(200);
  expect(cursorResponse.headers()['cache-control']).toContain('no-store');
  const secondPageText = await cursorResponse.text();
  const secondPage = JSON.parse(secondPageText) as {
    items: Array<{ id: string; ordinal: number }>;
    nextCursor: string | null;
  };
  expect(secondPage.items).toHaveLength(1);
  expect(secondPage.items[0].ordinal).toBe(26);
  expect(secondPage.nextCursor).toBeNull();
  expect(secondPageText).not.toMatch(/"(?:sourceText|normalizedContent|segments|content)"\s*:/i);
  await expect(page.getByText('Live Outline Seed 26', { exact: true })).toBeVisible();
  await expectNoAxeViolations(page);
  const storedValues = await page.evaluate(() => ({
    local: Object.values(localStorage),
    session: Object.values(sessionStorage),
  }));
  expect(JSON.stringify(storedValues)).not.toContain(firstPage.nextCursor!);

  const addButton = page.getByRole('button', { name: 'Thêm chương' });
  await addButton.focus();
  await page.keyboard.press('Enter');
  await expectNoAxeViolations(page);
  await page.screenshot({ path: auditScreenshot('add-chapter-form-360x800') });
  const input = page.getByLabel('Tên chương');
  await expect(input).toBeFocused();
  await page.keyboard.type(newChapterTitle);
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: 'Hủy' })).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: 'Thêm chương trống' })).toBeFocused();
  const csrfPromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/auth/csrf',
  );
  const createPromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === `/api/v1/authoring/manuscripts/${workId}/chapters`,
  );
  await page.keyboard.press('Enter');
  const [csrf, create] = await Promise.all([csrfPromise, createPromise]);
  expect(csrf.status()).toBe(200);
  expect(create.status()).toBe(201);

  const token = ((await csrf.json()) as { token: string }).token;
  const headers = create.request().headers();
  expect(headers['x-csrf-token']).toBe(token);
  expect(headers['idempotency-key']).toMatch(/^[A-Za-z0-9._~-]{16,128}$/);
  const idempotencyKey = headers['idempotency-key'];
  expect(create.request().postDataJSON()).toEqual({ title: newChapterTitle });
  const created = (await create.json()) as {
    id: string;
    workId: string;
    ordinal: number;
    title: string;
    status: string;
  };
  expect(created).toMatchObject({
    workId,
    ordinal: 27,
    title: newChapterTitle,
    status: 'draft',
  });
  expect(created.id).toMatch(/^[0-9a-f-]{36}$/i);
  await expect(page.getByRole('status').filter({ hasText: 'Đã thêm Chương 27' })).toBeVisible();
  await expect(page.locator('.authoring-outline-manuscript-meta')).toContainText('27 chương');
  const createdRow = page.getByRole('listitem').filter({ hasText: newChapterTitle });
  await expect(createdRow.getByText(newChapterTitle, { exact: true })).toBeVisible();
  await expect(createdRow.getByText('Chưa lưu nội dung', { exact: true })).toBeVisible();
  await expect(page.getByRole('listitem')).toHaveCount(27);
  await expect(page.getByRole('button', { name: 'Thêm chương' })).toBeFocused();

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

    const outsiderCsrfResponse = await page.request.get(`${baseUrl}/api/v1/auth/csrf`);
    expect(outsiderCsrfResponse.status()).toBe(200);
    const outsiderCsrf = (await outsiderCsrfResponse.json()) as { token: string };
    expect(outsiderCsrf.token).toMatch(/^[A-Za-z0-9_-]{43}$/);
    const foreignDelete = await page.request.delete(
      `${baseUrl}/api/v1/authoring/manuscripts/${workId}/chapters/${created.id}`,
      { headers: { 'X-CSRF-Token': outsiderCsrf.token } },
    );
    expect(foreignDelete.status()).toBe(404);
    expect(foreignDelete.headers()['cache-control']).toContain('no-store');
    const foreignDeleteBody = await foreignDelete.text();
    expect(foreignDeleteBody).toContain('DRAFT_CHAPTER_NOT_FOUND');
    expect(foreignDeleteBody).not.toContain(newChapterTitle);

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
    const ownerOutlineAfterForeignDelete = await page.request.get(
      `${baseUrl}/api/v1/authoring/manuscripts/${workId}/chapters?limit=100`,
    );
    expect(ownerOutlineAfterForeignDelete.status()).toBe(200);
    expect(ownerOutlineAfterForeignDelete.headers()['cache-control']).toContain('no-store');
    const ownerOutlineAfterForeignDeleteBody = (await ownerOutlineAfterForeignDelete.json()) as {
      items: Array<{ id: string; ordinal: number; title: string; status: string }>;
      nextCursor: string | null;
    };
    expect(ownerOutlineAfterForeignDeleteBody.items).toContainEqual(
      expect.objectContaining({
        id: created.id,
        ordinal: 27,
        title: newChapterTitle,
        status: 'draft',
      }),
    );
    expect(ownerOutlineAfterForeignDeleteBody.nextCursor).toBeNull();
  }

  const deleteTrigger = page.getByRole('button', {
    name: `Xóa chương trống Chương 27: ${newChapterTitle}`,
  });
  await deleteTrigger.click();
  await expect(page.getByText(/không thể khôi phục/)).toBeVisible();
  await expectNoAxeViolations(page);
  await page.getByRole('button', { name: 'Giữ lại' }).click();
  await expect(deleteTrigger).toBeFocused();
  await expect(page.getByText(/không thể khôi phục/)).toHaveCount(0);

  const replay = await page.request.post(
    `${baseUrl}/api/v1/authoring/manuscripts/${workId}/chapters`,
    {
      headers: { 'X-CSRF-Token': token, 'Idempotency-Key': idempotencyKey },
      data: { title: newChapterTitle },
    },
  );
  expect(replay.status()).toBe(201);
  expect(await replay.json()).toMatchObject({
    id: created.id,
    workId,
    ordinal: 27,
    title: newChapterTitle,
    status: 'draft',
  });

  await page.getByRole('button', { name: 'Sửa thông tin' }).click();
  const titleInput = page.locator('#authoring-metadata-title');
  await expect(titleInput).toBeFocused();
  await titleInput.fill('Live E2E Edited Novel');
  await page.locator('#authoring-metadata-author').fill('E2E Edited Pen');
  await page.locator('#authoring-metadata-language').fill('ja');
  await expectNoAxeViolations(page);
  await page.screenshot({ path: auditScreenshot('metadata-edit-360x800') });
  await page.setViewportSize({ width: 180, height: 400 });
  await expectNoAxeViolations(page);
  const narrowMetadataWidths = await page.evaluate(() => ({
    client: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth,
  }));
  expect(narrowMetadataWidths.scroll).toBeLessThanOrEqual(narrowMetadataWidths.client);
  const saveMetadataButton = page.getByRole('button', { name: 'Lưu thông tin' });
  await saveMetadataButton.scrollIntoViewIfNeeded();
  const narrowMetadataSave = await saveMetadataButton.boundingBox();
  const narrowMetadataNav = await page.locator('.mobile-nav').boundingBox();
  expect(narrowMetadataSave).not.toBeNull();
  expect(narrowMetadataNav).not.toBeNull();
  expect(narrowMetadataSave!.y + narrowMetadataSave!.height).toBeLessThanOrEqual(
    narrowMetadataNav!.y,
  );
  await page.screenshot({ path: auditScreenshot('metadata-edit-effective-200-180x400') });
  await page.setViewportSize({ width: 360, height: 800 });
  const firstMetadataCsrfPromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/auth/csrf',
  );
  const firstMetadataUpdatePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'PUT' &&
      new URL(response.url()).pathname === `/api/v1/authoring/manuscripts/${workId}`,
  );
  await page.getByRole('button', { name: 'Lưu thông tin' }).click();
  const [firstMetadataCsrf, firstMetadataUpdate] = await Promise.all([
    firstMetadataCsrfPromise,
    firstMetadataUpdatePromise,
  ]);
  const metadataCsrfToken = ((await firstMetadataCsrf.json()) as { token: string }).token;
  expect(firstMetadataUpdate.status()).toBe(200);
  expect(firstMetadataUpdate.request().headers()['x-csrf-token']).toBe(metadataCsrfToken);
  expect(firstMetadataUpdate.request().headers()['if-match']).toBe('"manuscript-metadata-1"');
  expect(firstMetadataUpdate.request().postDataJSON()).toEqual({
    title: 'Live E2E Edited Novel',
    author: 'E2E Edited Pen',
    language: 'ja',
  });
  expect(firstMetadataUpdate.headers().etag).toBe('"manuscript-metadata-2"');
  expect(firstMetadataUpdate.headers()['cache-control']).toContain('no-store');
  await expect(page.getByRole('heading', { name: 'Live E2E Edited Novel' })).toBeVisible();
  await expect(page.locator('.authoring-outline-manuscript-meta')).toContainText(
    'E2E Edited Pen · Ngôn ngữ ja · 27 chương',
  );
  await expect(page.getByRole('button', { name: 'Sửa thông tin' })).toBeFocused();

  const competingUpdate = await page.request.put(
    `${baseUrl}/api/v1/authoring/manuscripts/${workId}`,
    {
      headers: {
        Cookie: `__Host-novel_session=${sessionToken}`,
        'X-CSRF-Token': metadataCsrfToken,
        'If-Match': '"manuscript-metadata-2"',
      },
      data: { title: 'Server competing title', author: 'Server pen', language: 'en' },
    },
  );
  expect(competingUpdate.status()).toBe(200);
  expect(competingUpdate.headers().etag).toBe('"manuscript-metadata-3"');

  await page.getByRole('button', { name: 'Sửa thông tin' }).click();
  await page.locator('#authoring-metadata-title').fill('My explicit title');
  const staleCsrfPromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/auth/csrf',
  );
  const staleWritePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'PUT' &&
      new URL(response.url()).pathname === `/api/v1/authoring/manuscripts/${workId}`,
  );
  const conflictReloadPromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === `/api/v1/authoring/manuscripts/${workId}`,
  );
  await page.setViewportSize({ width: 180, height: 400 });
  await page.getByRole('button', { name: 'Lưu thông tin' }).click();
  const [, staleWrite, conflictReload] = await Promise.all([
    staleCsrfPromise,
    staleWritePromise,
    conflictReloadPromise,
  ]);
  expect(staleWrite.status()).toBe(412);
  expect(staleWrite.headers().etag).toBe('"manuscript-metadata-3"');
  expect(await staleWrite.text()).not.toContain('My explicit title');
  expect(conflictReload.status()).toBe(200);
  const useServerMetadata = page.getByRole('button', { name: 'Dùng thông tin trên máy chủ' });
  await expect(useServerMetadata).toBeVisible();
  await expect(useServerMetadata).toBeFocused();
  await expect(page.locator('#authoring-metadata-title')).toHaveValue('My explicit title');
  await expect(page.getByText(/Server competing title/)).toBeVisible();
  await expectNoAxeViolations(page);
  const narrowConflictWidths = await page.evaluate(() => ({
    client: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth,
  }));
  expect(narrowConflictWidths.scroll).toBeLessThanOrEqual(narrowConflictWidths.client);
  const narrowConflictButton = await useServerMetadata.boundingBox();
  const narrowMobileNav = await page.locator('.mobile-nav').boundingBox();
  expect(narrowConflictButton).not.toBeNull();
  expect(narrowMobileNav).not.toBeNull();
  expect(narrowConflictButton!.y + narrowConflictButton!.height).toBeLessThanOrEqual(
    narrowMobileNav!.y,
  );
  await page.screenshot({ path: auditScreenshot('metadata-conflict-effective-200-180x400') });
  const keepMyMetadata = page.getByRole('button', { name: 'Lưu thông tin của tôi' });
  await page.keyboard.press('Tab');
  await expect(keepMyMetadata).toBeFocused();
  const keepMineBox = await keepMyMetadata.boundingBox();
  expect(keepMineBox).not.toBeNull();
  expect(keepMineBox!.y + keepMineBox!.height).toBeLessThanOrEqual(narrowMobileNav!.y);
  await expectNoAxeViolations(page);
  await page.screenshot({
    path: auditScreenshot('metadata-conflict-keep-mine-effective-200-180x400'),
  });
  await page.keyboard.press('Shift+Tab');
  await expect(useServerMetadata).toBeFocused();
  await page.setViewportSize({ width: 360, height: 800 });
  await useServerMetadata.scrollIntoViewIfNeeded();
  await expectNoAxeViolations(page);
  await page.screenshot({ path: auditScreenshot('metadata-conflict-360x800') });

  const retryCsrfPromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/auth/csrf',
  );
  const retryWritePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'PUT' &&
      new URL(response.url()).pathname === `/api/v1/authoring/manuscripts/${workId}`,
  );
  await page.getByRole('button', { name: 'Lưu thông tin của tôi' }).click();
  const [, retryWrite] = await Promise.all([retryCsrfPromise, retryWritePromise]);
  expect(retryWrite.status()).toBe(200);
  expect(retryWrite.request().headers()['if-match']).toBe('"manuscript-metadata-3"');
  expect(retryWrite.headers().etag).toBe('"manuscript-metadata-4"');
  expect(retryWrite.request().postDataJSON()).toEqual({
    title: 'My explicit title',
    author: 'E2E Edited Pen',
    language: 'ja',
  });
  await expect(page.getByRole('heading', { name: 'My explicit title' })).toBeVisible();
  await expect(page.locator('.authoring-outline-manuscript-meta')).toContainText('27 chương');
  const metadataStorage = await page.evaluate(() => ({
    local: Object.values(localStorage),
    session: Object.values(sessionStorage),
  }));
  expect(JSON.stringify(metadataStorage)).not.toContain('My explicit title');
  await expectNoAxeViolations(page);

  const widths = await page.evaluate(() => ({
    client: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth,
  }));
  expect(widths.scroll).toBeLessThanOrEqual(widths.client);
  await page.screenshot({ path: screenshotPath!, fullPage: true });

  for (const viewport of [
    { width: 640, height: 450 },
    { width: 1280, height: 900 },
  ]) {
    await page.setViewportSize(viewport);
    await page.getByRole('button', { name: 'Thêm chương', exact: true }).click();
    await expect(page.getByLabel('Tên chương')).toBeVisible();
    const responsiveWidths = await page.evaluate(() => ({
      client: document.documentElement.clientWidth,
      scroll: document.documentElement.scrollWidth,
    }));
    expect(responsiveWidths.scroll).toBeLessThanOrEqual(responsiveWidths.client);
    await expectNoAxeViolations(page);
    await page.getByRole('button', { name: 'Hủy' }).click();
    await expect(page.getByLabel('Tên chương')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Thêm chương', exact: true })).toBeFocused();
  }

  await page.setViewportSize({ width: 360, height: 800 });
  await page
    .getByRole('button', {
      name: `Xóa chương trống Chương 27: ${newChapterTitle}`,
    })
    .click();
  await expect(page.getByText(/không thể khôi phục/)).toBeVisible();
  await expectNoAxeViolations(page);
  await page.screenshot({
    path: screenshotPath!.replace(
      /authoring-outline-live\.png$/,
      'authoring-empty-chapter-delete-confirm-live.png',
    ),
    fullPage: true,
  });
  const deleteCsrfPromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/auth/csrf',
  );
  const deletePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'DELETE' &&
      new URL(response.url()).pathname ===
        `/api/v1/authoring/manuscripts/${workId}/chapters/${created.id}`,
  );
  await page.getByRole('button', { name: 'Xác nhận xóa' }).click();
  const [deleteCsrf, deletion] = await Promise.all([deleteCsrfPromise, deletePromise]);
  const deleteToken = ((await deleteCsrf.json()) as { token: string }).token;
  expect(deletion.status()).toBe(204);
  expect(deletion.request().headers()['x-csrf-token']).toBe(deleteToken);
  expect(deletion.headers()['cache-control']).toContain('no-store');
  await expect(page.getByText(newChapterTitle, { exact: true })).toHaveCount(0);
  await expect(page.locator('.authoring-outline-manuscript-meta')).toContainText('26 chương');
  await expect(page.getByRole('status').filter({ hasText: 'Đã xóa chương trống' })).toBeVisible();
  await expect(page.locator('#authoring-chapter-row-' + secondPage.items[0].id)).toBeFocused();
  await expectNoAxeViolations(page);
  await page.screenshot({
    path: screenshotPath!.replace(
      /authoring-outline-live\.png$/,
      'authoring-empty-chapter-delete-live.png',
    ),
    fullPage: true,
  });
});
