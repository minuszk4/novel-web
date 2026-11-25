import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AuthoringSharedPageComponent } from './authoring-shared-page.component';

const workId = '11111111-1111-4111-8111-111111111111';
const chapterId = '22222222-2222-4222-8222-222222222222';
const timestamp = '2026-11-25T01:00:00Z';

describe('AuthoringSharedPageComponent', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<AuthoringSharedPageComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AuthoringSharedPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AuthoringSharedPageComponent);
  });

  afterEach(() => {
    http.verify();
    fixture.destroy();
    TestBed.resetTestingModule();
  });

  it('shows a saved draft as text for VIEW and does not offer edit controls', async () => {
    fixture.detectChanges();
    http.expectOne('/api/v1/authoring/collaborations').flush(
      {
        items: [
          {
            workId,
            title: 'Bản thảo riêng',
            author: 'Bút danh',
            permissions: ['VIEW'],
            grantedAt: timestamp,
          },
        ],
      },
      { headers: { 'Cache-Control': 'no-store' } },
    );
    await settle();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Chỉ xem');
    expect(fixture.nativeElement.querySelector('a[href*="/chapters"]')).toBeNull();

    (
      fixture.nativeElement.querySelector('.shared-work-actions button') as HTMLButtonElement
    ).click();
    http.expectOne(`/api/v1/authoring/manuscripts/${workId}/chapters?limit=25`).flush({
      items: [
        {
          id: chapterId,
          ordinal: 1,
          title: 'Chương đã lưu',
          language: 'vi',
          status: 'draft',
          contentAvailable: false,
          hasSavedDraftContent: true,
          createdAt: timestamp,
        },
      ],
      nextCursor: null,
    });
    await settle();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Có nội dung đã lưu');
    (fixture.nativeElement.querySelector('.shared-chapters button') as HTMLButtonElement).click();
    http.expectOne(`/api/v1/authoring/manuscripts/${workId}/chapters/${chapterId}/draft`).flush(
      {
        id: chapterId,
        workId,
        title: 'Chương đã lưu',
        status: 'draft',
        revisionNo: 1,
        sourceFormat: 'plain_text',
        normalizedContent: 'Một đoạn văn đã lưu.',
        segments: [
          {
            ordinal: 1,
            type: 'paragraph',
            sourceText: 'Một đoạn văn đã lưu.',
            stableAnchor: 'chapter-22222222-p1',
          },
        ],
        updatedAt: timestamp,
      },
      { headers: { 'Cache-Control': 'no-store', ETag: '"draft-1"' } },
    );
    await settle();
    fixture.detectChanges();
    const preview = fixture.nativeElement.querySelector('.shared-draft-content') as HTMLElement;
    expect(preview.textContent).toContain('Một đoạn văn đã lưu.');
    expect(fixture.nativeElement.querySelector('textarea')).toBeNull();
    expect(fixture.nativeElement.querySelector('a[href*="/chapters"]')).toBeNull();
  });
});

async function settle(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
}
