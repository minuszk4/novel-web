import { expect, test } from './support';

const viewports = [
  { name: 'mobile 360px', width: 360, height: 800 },
  { name: 'tablet 768px', width: 768, height: 1024 },
  { name: 'desktop 1280px', width: 1280, height: 900 },
] as const;

for (const viewport of viewports) {
  test(`Reader offers a visible route back to work detail at ${viewport.name}`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto('/read/demo-story/chapter-1');

    const backLink = page.getByRole('link', { name: 'Quay lại chi tiết tác phẩm' });
    await expect(backLink).toBeVisible();
    await expect(backLink).toHaveAttribute('href', '/works/demo-story');

    const target = await backLink.boundingBox();
    expect(target).not.toBeNull();
    expect(target!.width).toBeGreaterThanOrEqual(40);
    expect(target!.height).toBeGreaterThanOrEqual(40);

    await backLink.click();
    await expect(page).toHaveURL(/\/works\/demo-story$/);
    await expect(page.getByRole('heading', { name: 'Bưu Điện Cuối Con Đường Mưa' })).toBeVisible();
  });
}
