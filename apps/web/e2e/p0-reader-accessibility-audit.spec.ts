import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import { expect, expectNoAxeViolations, test } from './support';
import type { Page } from '@playwright/test';

const screenshotDirectory = process.env['NOVEL_ACCESSIBILITY_CAPTURE_DIR'];

async function capture(page: Page, name: string): Promise<void> {
  if (!screenshotDirectory) return;
  await mkdir(screenshotDirectory, { recursive: true });
  await page.screenshot({ path: path.join(screenshotDirectory, `${name}.png`) });
}

test('P0 sign-in to Reader journey is usable with keyboard and reflows at effective 200%', async ({
  browser,
  page,
}) => {
  test.setTimeout(180_000);
  await page.setViewportSize({ width: 360, height: 800 });

  await page.goto('/sign-in');
  await expect(page.getByRole('heading', { name: 'Đăng nhập vào Novel' })).toBeVisible();
  await expectNoAxeViolations(page);
  await capture(page, '01-sign-in-360x800');

  await page.getByRole('link', { name: /Tiếp tục với bản demo/ }).click();
  await expect(page.getByRole('heading', { name: 'Một vài điều cần biết' })).toBeVisible();
  await expect(page.getByRole('note')).toContainText('Đang ở chế độ mô phỏng');
  await expectNoAxeViolations(page);
  await capture(page, '02-setup-360x800');

  const termsConsent = page.getByRole('checkbox').nth(0);
  const privacyConsent = page.getByRole('checkbox').nth(1);
  await termsConsent.focus();
  await page.keyboard.press('Space');
  await expect(termsConsent).toBeChecked();
  await privacyConsent.focus();
  await page.keyboard.press('Space');
  await expect(privacyConsent).toBeChecked();
  const enterWorkspace = page.getByRole('button', { name: /Vào không gian đọc/ });
  await enterWorkspace.focus();
  await capture(page, '03-setup-ready-360x800');
  await enterWorkspace.click();
  await expect(page.getByRole('heading', { name: /Chào bạn, độc giả/ })).toBeVisible();
  await expectNoAxeViolations(page);
  await capture(page, '04-home-360x800');

  await page.locator('.mobile-nav').getByRole('link', { name: 'Thư viện' }).click();
  await expect(page.getByRole('heading', { name: 'Chưa có gì trên kệ' })).toBeVisible();
  await expectNoAxeViolations(page);
  await capture(page, '05-library-360x800');

  await page.getByRole('link', { name: 'Thêm tác phẩm', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Mang câu chuyện của bạn về đây' })).toBeVisible();
  await expectNoAxeViolations(page);
  await capture(page, '06-import-360x800');

  await page.getByRole('link', { name: 'Về trang chủ' }).click();
  await expect(page.getByRole('heading', { name: /Chào bạn, độc giả/ })).toBeVisible();
  await page.getByRole('link', { name: 'Thử trình đọc' }).click();
  await expect(page.getByRole('heading', { name: 'Bưu Điện Cuối Con Đường Mưa' })).toBeVisible();
  await expectNoAxeViolations(page);
  await capture(page, '07-work-detail-360x800');

  await page.getByRole('link', { name: /Đọc chương đầu/ }).click();
  await expect(page.locator('.reader-article h1')).toBeVisible();
  await expectNoAxeViolations(page);
  await capture(page, '08-reader-360x800');

  const tocButton = page.getByRole('button', { name: 'Mở mục lục' });
  await tocButton.click();
  const tocDialog = page.getByRole('dialog', { name: 'Chương mẫu' });
  await expect(tocDialog).toBeVisible();
  await expect(tocDialog).toContainText('Chương 1');
  await expectNoAxeViolations(page);
  await capture(page, '09-reader-toc-360x800');
  await page.keyboard.press('Escape');
  await expect(tocDialog).toBeHidden();
  await expect(tocButton).toBeFocused();

  const settingsButton = page.getByRole('button', { name: 'Cài đặt trình đọc' });
  await settingsButton.click();
  const settingsDialog = page.getByRole('dialog', { name: 'Cài đặt đọc' });
  await expect(settingsDialog).toBeVisible();
  await expect(settingsDialog.getByRole('button', { name: 'Đóng cài đặt' })).toBeFocused();
  await expectNoAxeViolations(page);
  await capture(page, '10-reader-settings-360x800');

  const nextChapter = page.getByRole('link', { name: /Chương tiếp/ });
  const nextChapterBounds = await nextChapter.boundingBox();
  expect(nextChapterBounds).not.toBeNull();
  const chapterOneUrl = page.url();
  await page.mouse.click(
    nextChapterBounds!.x + nextChapterBounds!.width / 2,
    nextChapterBounds!.y + nextChapterBounds!.height / 2,
  );
  await expect(page).toHaveURL(chapterOneUrl);
  await expect(settingsDialog).toBeHidden();
  await page.keyboard.press('Escape');
  await expect(settingsDialog).toBeHidden();
  await expect(settingsButton).toBeFocused();

  const zoomContext = await browser.newContext({
    viewport: { width: 180, height: 400 },
    deviceScaleFactor: 2,
  });
  try {
    const zoomPage = await zoomContext.newPage();
    await zoomPage.goto('/read/demo-story/chapter-1');
    await expect(zoomPage.locator('.reader-article h1')).toBeVisible();
    const dimensions = await zoomPage.evaluate(() => ({
      cssWidth: window.innerWidth,
      devicePixelRatio: window.devicePixelRatio,
      documentWidth: document.documentElement.scrollWidth,
      bodyWidth: document.body.scrollWidth,
    }));
    expect(dimensions).toEqual({
      cssWidth: 180,
      devicePixelRatio: 2,
      documentWidth: 180,
      bodyWidth: 180,
    });
    await expectNoAxeViolations(zoomPage);
    await capture(zoomPage, '11-reader-effective-200-180x400-dpr2');
  } finally {
    await zoomContext.close();
  }
});
