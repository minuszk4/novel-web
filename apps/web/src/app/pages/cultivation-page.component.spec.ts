import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { DemoStateService } from '../core/demo-state.service';
import { CultivationPageComponent } from './cultivation-page.component';

describe('CultivationPageComponent', () => {
  it('shows the example rank, next threshold, and accessible progress', () => {
    const fixture = TestBed.createComponent(CultivationPageComponent);
    fixture.detectChanges();

    const element: HTMLElement = fixture.nativeElement;
    const progress = element.querySelector('[role="progressbar"]');

    expect(element.querySelector('h3')?.textContent?.trim()).toBe('Luyện Khí tầng 6');
    expect(element.querySelector('.rank-next-box strong')?.textContent?.trim()).toBe(
      'Luyện Khí tầng 7',
    );
    expect(element.querySelector('.rank-next-box span')?.textContent?.trim()).toBe('700 XP');
    expect(progress?.getAttribute('aria-valuenow')).toBe('20');
    expect(progress?.getAttribute('aria-label')).toBe('Ví dụ tiến độ tới Luyện Khí tầng 7');
  });

  it('keeps the complete cultivation ladder in ascending threshold order', () => {
    const fixture = TestBed.createComponent(CultivationPageComponent);
    fixture.detectChanges();

    const element: HTMLElement = fixture.nativeElement;
    const ranks = Array.from(element.querySelectorAll('.rank-ladder-copy strong')).map((rank) =>
      rank.textContent?.trim(),
    );

    expect(ranks).toEqual([
      'Phàm nhân',
      'Luyện Khí tầng 1',
      'Luyện Khí tầng 2',
      'Luyện Khí tầng 3',
      'Luyện Khí tầng 4',
      'Luyện Khí tầng 5',
      'Luyện Khí tầng 6',
      'Luyện Khí tầng 7',
      'Luyện Khí tầng 8',
      'Luyện Khí tầng 9',
      'Trúc Cơ',
      'Kim Đan',
      'Nguyên Anh',
      'Hóa Thần',
      'Luyện Hư',
      'Hợp Thể',
      'Đại Thừa',
      'Độ Kiếp',
    ]);
    expect(element.querySelector('.private-tag')?.textContent).toContain('KHÔNG BXH');
  });

  it('requires explicit opt-in and allows pause and resume', () => {
    const fixture = TestBed.createComponent(CultivationPageComponent);
    const demo = TestBed.inject(DemoStateService);
    fixture.detectChanges();

    expect(demo.cultivationEnabled()).toBe(false);
    const enableButton = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    expect(enableButton.textContent?.trim()).toBe('Bật trong bản demo');
    enableButton.click();
    fixture.detectChanges();

    expect(demo.cultivationEnabled()).toBe(true);
    expect(demo.cultivationPaused()).toBe(false);
    const pauseButton = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    expect(pauseButton.textContent?.trim()).toBe('Tạm dừng tích lũy');
    pauseButton.click();
    fixture.detectChanges();

    expect(demo.cultivationPaused()).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Đang tạm dừng.');
    const resumeButton = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    expect(resumeButton.textContent?.trim()).toBe('Tiếp tục');
    resumeButton.click();
    fixture.detectChanges();

    expect(demo.cultivationPaused()).toBe(false);
  });
});
