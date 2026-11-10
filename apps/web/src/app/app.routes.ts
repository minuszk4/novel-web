import { Routes } from '@angular/router';
import { AppShellComponent } from './shared/app-shell.component';
import { CultivationPageComponent } from './pages/cultivation-page.component';
import { HomePageComponent } from './pages/home-page.component';
import { LandingPageComponent } from './pages/landing-page.component';
import { LibraryPageComponent } from './pages/library-page.component';
import { SearchPageComponent } from './pages/search-page.component';
import { ReaderPageComponent } from './pages/reader-page.component';
import { SettingsPageComponent } from './pages/settings-page.component';
import { SetupPageComponent } from './pages/setup-page.component';
import { SignInPageComponent } from './pages/sign-in-page.component';
import { WorkDetailPageComponent } from './pages/work-detail-page.component';
import { policyConsentGuard } from './core/policy-consent.guard';

export const routes: Routes = [
  { path: '', component: LandingPageComponent, title: 'Novel — Đọc theo cách của bạn' },
  { path: 'sign-in', component: SignInPageComponent, title: 'Đăng nhập — Novel' },
  { path: 'works/demo-story', component: WorkDetailPageComponent, title: 'Tác phẩm mẫu — Novel' },
  { path: 'read/demo-story/:chapterId', component: ReaderPageComponent, title: 'Đang đọc — Novel' },
  {
    path: 'app',
    component: AppShellComponent,
    canActivateChild: [policyConsentGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'home' },
      { path: 'setup', component: SetupPageComponent, title: 'Thiết lập tài khoản — Novel' },
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
      { path: 'home', component: HomePageComponent, title: 'Trang chủ — Novel' },
      { path: 'library', component: LibraryPageComponent, title: 'Thư viện — Novel' },
      { path: 'search', component: SearchPageComponent, title: 'Tìm trong thư viện — Novel' },
      {
        path: 'cultivation',
        component: CultivationPageComponent,
        title: 'Hành trình tu luyện — Novel',
      },
      { path: 'settings', component: SettingsPageComponent, title: 'Cài đặt — Novel' },
    ],
  },
  { path: '**', redirectTo: '' },
];
