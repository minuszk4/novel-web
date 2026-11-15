import { Routes } from '@angular/router';
import { AppShellComponent } from './shared/app-shell.component';
import { policyConsentGuard } from './core/policy-consent.guard';
import { glossaryEditorUnsavedGuard } from './pages/glossary-editor-unsaved.guard';
import { authoringChapterEditorUnsavedGuard } from './pages/authoring-chapter-editor-unsaved.guard';
import { authoringOutlineUnsavedGuard } from './pages/authoring-outline-unsaved.guard';
import { translationCandidateEditUnsavedGuard } from './pages/translation-candidate-edit-unsaved.guard';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./pages/landing-page.component').then((page) => page.LandingPageComponent),
    title: 'Novel — Đọc theo cách của bạn',
  },
  {
    path: 'sign-in',
    loadComponent: () =>
      import('./pages/sign-in-page.component').then((page) => page.SignInPageComponent),
    title: 'Đăng nhập — Novel',
  },
  {
    path: 'works/demo-story',
    loadComponent: () =>
      import('./pages/work-detail-page.component').then((page) => page.WorkDetailPageComponent),
    title: 'Tác phẩm mẫu — Novel',
  },
  {
    path: 'read/demo-story/:chapterId',
    loadComponent: () =>
      import('./pages/reader-page.component').then((page) => page.ReaderPageComponent),
    title: 'Đang đọc — Novel',
  },
  {
    path: 'app',
    component: AppShellComponent,
    canActivateChild: [policyConsentGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'home' },
      {
        path: 'setup',
        loadComponent: () =>
          import('./pages/setup-page.component').then((page) => page.SetupPageComponent),
        title: 'Thiết lập tài khoản — Novel',
      },
      {
        path: 'import',
        loadComponent: () =>
          import('./pages/import-page.component').then((page) => page.ImportPageComponent),
        title: 'Nhập truyện — Novel',
      },
      {
        path: 'import/review',
        loadComponent: () =>
          import('./pages/import-review-page.component').then(
            (page) => page.ImportReviewPageComponent,
          ),
        title: 'Xem lại truyện — Novel',
      },
      {
        path: 'authoring',
        pathMatch: 'full',
        loadComponent: () =>
          import('./pages/authoring-manuscripts-page.component').then(
            (page) => page.AuthoringManuscriptsPageComponent,
          ),
        title: 'Tác phẩm của tôi — Novel',
      },
      {
        path: 'authoring/new',
        loadComponent: () =>
          import('./pages/authoring-create-page.component').then(
            (page) => page.AuthoringCreatePageComponent,
          ),
        title: 'Tạo bản thảo — Novel',
      },
      {
        path: 'authoring/:workId/chapters',
        loadComponent: () =>
          import('./pages/authoring-outline-page.component').then(
            (page) => page.AuthoringOutlinePageComponent,
          ),
        canDeactivate: [authoringOutlineUnsavedGuard],
        title: 'Dàn ý bản thảo — Novel',
      },
      {
        path: 'authoring/:workId/chapters/:chapterId',
        loadComponent: () =>
          import('./pages/authoring-chapter-editor-page.component').then(
            (page) => page.AuthoringChapterEditorPageComponent,
          ),
        canDeactivate: [authoringChapterEditorUnsavedGuard],
        title: 'Soạn chương — Novel',
      },
      {
        path: 'home',
        loadComponent: () =>
          import('./pages/home-page.component').then((page) => page.HomePageComponent),
        title: 'Trang chủ — Novel',
      },
      {
        path: 'library',
        loadComponent: () =>
          import('./pages/library-page.component').then((page) => page.LibraryPageComponent),
        title: 'Thư viện — Novel',
      },
      {
        path: 'history',
        loadComponent: () =>
          import('./pages/reading-history-page.component').then(
            (page) => page.ReadingHistoryPageComponent,
          ),
        title: 'Lịch sử đọc — Novel',
      },
      {
        path: 'works/:workId',
        loadComponent: () =>
          import('./pages/account-work-detail-page.component').then(
            (page) => page.AccountWorkDetailPageComponent,
          ),
        title: 'Chi tiết tác phẩm — Novel',
      },
      {
        path: 'reader/:workId',
        loadComponent: () =>
          import('./pages/account-reader-page.component').then(
            (page) => page.AccountReaderPageComponent,
          ),
        title: 'Đang đọc — Novel',
      },
      {
        path: 'reader/:workId/:chapterId',
        loadComponent: () =>
          import('./pages/account-reader-page.component').then(
            (page) => page.AccountReaderPageComponent,
          ),
        title: 'Đang đọc — Novel',
      },
      {
        path: 'translation',
        loadComponent: () =>
          import('./pages/translation-desk-page.component').then(
            (page) => page.TranslationDeskPageComponent,
          ),
        title: 'Bàn dịch — Novel',
      },
      {
        path: 'translation/jobs/:jobId/candidate',
        loadComponent: () =>
          import('./pages/translation-candidate-preview-page.component').then(
            (page) => page.TranslationCandidatePreviewPageComponent,
          ),
        canDeactivate: [translationCandidateEditUnsavedGuard],
        title: 'Đối chiếu candidate giả — Novel',
      },
      {
        path: 'translation/jobs/:jobId',
        loadComponent: () =>
          import('./pages/translation-job-page.component').then(
            (page) => page.TranslationJobPageComponent,
          ),
        title: 'Bản xem thử dịch — Novel',
      },
      {
        path: 'search',
        loadComponent: () =>
          import('./pages/search-page.component').then((page) => page.SearchPageComponent),
        title: 'Tìm trong thư viện — Novel',
      },
      {
        path: 'glossaries',
        loadComponent: () =>
          import('./pages/glossary-catalog-page.component').then(
            (page) => page.GlossaryCatalogPageComponent,
          ),
        title: 'Thuật ngữ — Novel',
      },
      {
        path: 'glossaries/:glossaryId/import',
        loadComponent: () =>
          import('./pages/glossary-import-page.component').then(
            (page) => page.GlossaryImportPageComponent,
          ),
        title: 'Nhập thuật ngữ — Novel',
      },
      {
        path: 'glossaries/:glossaryId/edit',
        loadComponent: () =>
          import('./pages/glossary-editor-page.component').then(
            (page) => page.GlossaryEditorPageComponent,
          ),
        canDeactivate: [glossaryEditorUnsavedGuard],
        title: 'Biên tập thuật ngữ — Novel',
      },
      {
        path: 'glossaries/:glossaryId',
        loadComponent: () =>
          import('./pages/glossary-detail-page.component').then(
            (page) => page.GlossaryDetailPageComponent,
          ),
        title: 'Chi tiết glossary — Novel',
      },
      {
        path: 'cultivation',
        loadComponent: () =>
          import('./pages/cultivation-page.component').then(
            (page) => page.CultivationPageComponent,
          ),
        title: 'Hành trình tu luyện — Novel',
      },
      {
        path: 'settings',
        loadComponent: () =>
          import('./pages/settings-page.component').then((page) => page.SettingsPageComponent),
        title: 'Cài đặt — Novel',
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
