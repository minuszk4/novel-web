import { expect, expectNoAxeViolations, test } from './support';

const settingsViewports = [
  { name: 'mobile 360×800', width: 360, height: 800 },
  { name: 'short landscape 640×360', width: 640, height: 360 },
  { name: 'desktop 1280×900', width: 1280, height: 900 },
] as const;

for (const viewport of settingsViewports) {
  test(`settings remain operable and communicate state at ${viewport.name}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto('/app/settings');
    await expect(page.getByRole('heading', { name: 'Cài đặt', exact: true })).toBeVisible();
    await expectNoAxeViolations(page);
    await page.screenshot({
      path: testInfo.outputPath(`01-settings-default-${viewport.width}x${viewport.height}.png`),
      fullPage: false,
    });

    const targetBounds = await page
      .locator(
        '.settings-nav a, .settings-theme-picker button, .settings-inline-action .button, .account-demo-card .text-link, .toggle-row',
      )
      .evaluateAll((elements) =>
        elements.map((element) => {
          const bounds = element.getBoundingClientRect();
          return {
            label: element.textContent?.trim(),
            width: bounds.width,
            height: bounds.height,
          };
        }),
      );
    for (const target of targetBounds) {
      expect(target.width, `${target.label} target width`).toBeGreaterThanOrEqual(44);
      expect(target.height, `${target.label} target height`).toBeGreaterThanOrEqual(44);
    }

    const light = page.getByRole('button', { name: 'Sáng' });
    const sepia = page.getByRole('button', { name: 'Sepia' });
    const dark = page.getByRole('button', { name: 'Tối' });
    await expect(light).toHaveAttribute('aria-pressed', 'true');
    await expect(sepia).toHaveAttribute('aria-pressed', 'false');
    await expect(dark).toHaveAttribute('aria-pressed', 'false');

    const fontSize = page.locator('#font-size-setting');
    await fontSize.focus();
    await fontSize.press('End');
    await expect(fontSize).toHaveValue('28');
    await sepia.focus();
    await sepia.press('Enter');
    await expect(sepia).toHaveAttribute('aria-pressed', 'true');
    await expect(light).toHaveAttribute('aria-pressed', 'false');
    await expectNoAxeViolations(page);
    await page.screenshot({
      path: testInfo.outputPath(`02-settings-changed-${viewport.width}x${viewport.height}.png`),
      fullPage: false,
    });

    const analytics = page.getByRole('checkbox', { name: /Phân tích sử dụng/ });
    const providerSharing = page.getByRole('checkbox', {
      name: /Chia sẻ nội dung với dịch vụ bên ngoài/,
    });
    await expect(analytics).not.toBeChecked();
    await expect(providerSharing).not.toBeChecked();
    await analytics.focus();
    await analytics.press('Space');
    await expect(analytics).toBeChecked();
    await expect(providerSharing).not.toBeChecked();
    await expect(analytics).toBeFocused();
    await analytics.evaluate((element) =>
      element.scrollIntoView({ block: 'center', behavior: 'instant' }),
    );
    await page.screenshot({
      path: testInfo.outputPath(
        `03-settings-privacy-enabled-${viewport.width}x${viewport.height}.png`,
      ),
      fullPage: false,
    });
    await providerSharing.focus();
    await providerSharing.press('Space');
    await expect(providerSharing).toBeChecked();
    await expect(analytics).toBeChecked();
    await page.getByRole('button', { name: 'Đặt lại' }).click();
    await expect(fontSize).toHaveValue('20');
    await expect(light).toHaveAttribute('aria-pressed', 'true');
    await expect(sepia).toHaveAttribute('aria-pressed', 'false');
    await expect(analytics).toBeChecked();
    await expect(providerSharing).toBeChecked();

    await expect(page.getByRole('link', { name: 'Xem trạng thái đăng nhập' })).toHaveAttribute(
      'href',
      '/sign-in',
    );
    if (viewport.width <= 900) {
      const signInStatusLink = page.getByRole('link', { name: 'Xem trạng thái đăng nhập' });
      await signInStatusLink.evaluate((element) =>
        element.scrollIntoView({ block: 'center', behavior: 'instant' }),
      );
      const linkBounds = await signInStatusLink.boundingBox();
      const mobileNavigationBounds = await page.locator('.mobile-nav').boundingBox();
      expect(linkBounds).not.toBeNull();
      expect(mobileNavigationBounds).not.toBeNull();
      expect(linkBounds!.y + linkBounds!.height).toBeLessThanOrEqual(mobileNavigationBounds!.y);
      await page.screenshot({
        path: testInfo.outputPath(
          `04-settings-account-link-${viewport.width}x${viewport.height}.png`,
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

for (const viewport of settingsViewports) {
  test(`demo Reader announces its selected theme at ${viewport.name}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto('/read/demo-story/chapter-1');
    await page.getByRole('button', { name: 'Cài đặt trình đọc' }).click();
    const dialog = page.getByRole('dialog', { name: 'Cài đặt đọc' });
    await expect(dialog).toBeVisible();
    const themes = dialog.getByRole('group', { name: 'Màu nền' });
    const light = themes.getByRole('button', { name: 'Sáng' });
    const sepia = themes.getByRole('button', { name: 'Sepia' });
    const dark = themes.getByRole('button', { name: 'Tối' });
    const fontDecrease = dialog.getByRole('button', { name: 'A−' });
    const fontIncrease = dialog.getByRole('button', { name: 'A+' });
    const reset = dialog.getByRole('button', { name: 'Đặt lại mặc định' });
    const close = dialog.getByRole('button', { name: 'Đóng cài đặt' });
    await page.screenshot({
      path: testInfo.outputPath(
        `05-demo-reader-theme-default-${viewport.width}x${viewport.height}.png`,
      ),
      fullPage: false,
    });
    await expect(light).toHaveAttribute('aria-pressed', 'true');
    await expect(sepia).toHaveAttribute('aria-pressed', 'false');
    await expect(dark).toHaveAttribute('aria-pressed', 'false');
    for (const control of [close, fontDecrease, fontIncrease, light, sepia, dark, reset]) {
      const bounds = await control.boundingBox();
      expect(bounds).not.toBeNull();
      expect(bounds!.width).toBeGreaterThanOrEqual(44);
      expect(bounds!.height).toBeGreaterThanOrEqual(44);
    }

    await fontIncrease.focus();
    await fontIncrease.press('Enter');
    await expect(dialog.locator('.reader-setting-row')).toContainText('21 px');
    await sepia.focus();
    await sepia.press('Enter');
    await expect(sepia).toHaveAttribute('aria-pressed', 'true');
    await dark.focus();
    await dark.press('Space');
    await expect(dark).toHaveAttribute('aria-pressed', 'true');
    await expect(sepia).toHaveAttribute('aria-pressed', 'false');
    await expectNoAxeViolations(page);
    await page.screenshot({
      path: testInfo.outputPath(
        `06-demo-reader-theme-dark-${viewport.width}x${viewport.height}.png`,
      ),
      fullPage: false,
    });
    await reset.evaluate((element) =>
      element.scrollIntoView({ block: 'center', behavior: 'instant' }),
    );
    await expect(reset).toBeInViewport();
    await reset.click();
    await expect(dialog.locator('.reader-setting-row')).toContainText('20 px');
    await expect(light).toHaveAttribute('aria-pressed', 'true');
    await expect(dark).toHaveAttribute('aria-pressed', 'false');
    const dimensions = await page.evaluate(() => ({
      document: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
    }));
    expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
    await expectNoAxeViolations(page);
    await page.screenshot({
      path: testInfo.outputPath(
        `07-demo-reader-settings-reset-${viewport.width}x${viewport.height}.png`,
      ),
      fullPage: false,
    });
  });
}
