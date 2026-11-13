import { Component, computed, inject, signal } from '@angular/core';
import { DemoStateService } from '../core/demo-state.service';
import {
  CultivationActivityPage,
  CultivationApiError,
  CultivationApiService,
  CultivationDailyActivity,
  CultivationProfile,
} from '../core/cultivation-api.service';
import { PolicyOnboardingService } from '../core/policy-onboarding.service';

type ProfilePhase = 'loading' | 'ready' | 'unavailable' | 'error';
type ActivityPhase = 'idle' | 'loading' | 'ready' | 'loading-more' | 'error';

@Component({
  selector: 'app-cultivation-page',
  templateUrl: './cultivation-page.component.html',
})
export class CultivationPageComponent {
  protected readonly demo = inject(DemoStateService);
  protected readonly policy = inject(PolicyOnboardingService);
  private readonly api = inject(CultivationApiService);
  protected readonly exampleXp = 620;
  protected readonly dailyCap = 120;
  protected readonly isAccountMode = computed(
    () => this.policy.status() === 'accepted' && this.policy.accountIdentity() !== null,
  );
  protected readonly profile = signal<CultivationProfile | null>(null);
  protected readonly profilePhase = signal<ProfilePhase>('loading');
  protected readonly profileMessage = signal<string | null>(null);
  protected readonly actionMessage = signal<string | null>(null);
  protected readonly isSavingSettings = signal(false);
  protected readonly activities = signal<CultivationDailyActivity[]>([]);
  protected readonly nextActivityCursor = signal<string | null>(null);
  protected readonly activityPhase = signal<ActivityPhase>('idle');
  protected readonly activityMessage = signal<string | null>(null);
  private readonly activityRetryCursor = signal<string | null>(null);

  get sampleRankIndex(): number {
    let index = 0;
    this.demo.cultivationRanks.forEach((rank, rankIndex) => {
      if (rank.threshold <= this.exampleXp) index = rankIndex;
    });
    return index;
  }

  get sampleRank(): string {
    return this.demo.cultivationRanks[this.sampleRankIndex]?.name ?? 'Phàm nhân';
  }

  get sampleNextRank(): string {
    return this.demo.cultivationRanks[this.sampleRankIndex + 1]?.name ?? 'Đã đạt mốc cuối';
  }

  get sampleNextThreshold(): number {
    return this.demo.cultivationRanks[this.sampleRankIndex + 1]?.threshold ?? this.exampleXp;
  }

  get sampleProgress(): number {
    const current = this.demo.cultivationRanks[this.sampleRankIndex]?.threshold ?? 0;
    const next = this.sampleNextThreshold;
    return next === current ? 100 : ((this.exampleXp - current) / (next - current)) * 100;
  }

  constructor() {
    if (this.isAccountMode()) void this.loadAccount();
  }

  async retryProfile(): Promise<void> {
    await this.loadAccount();
  }

  async toggleAccountProgression(): Promise<void> {
    const current = this.profile();
    if (!current || this.isSavingSettings()) return;
    this.isSavingSettings.set(true);
    this.actionMessage.set(null);
    try {
      this.profile.set(await this.api.setEnabled(!current.enabled));
    } catch (error) {
      this.actionMessage.set(this.errorMessage(error, 'Chưa lưu được lựa chọn. Hãy thử lại.'));
    } finally {
      this.isSavingSettings.set(false);
    }
  }

  async loadMoreActivity(): Promise<void> {
    const cursor = this.nextActivityCursor();
    if (!cursor || this.activityPhase() !== 'ready') return;
    await this.fetchActivity(cursor, true);
  }

  async retryActivity(): Promise<void> {
    if (this.activityPhase() !== 'error') return;
    const cursor = this.activityRetryCursor();
    await this.fetchActivity(cursor, cursor !== null);
  }

  rankProgressPercent(): number {
    return Math.round((this.profile()?.progressRatio ?? 0) * 10_000) / 100;
  }

  dailyProgressPercent(): number {
    const state = this.profile();
    if (!state || state.dailyXpCap < 1) return 0;
    return Math.min(100, (state.todayXpEarned / state.dailyXpCap) * 100);
  }

  formatActivityDate(value: string): string {
    return new Intl.DateTimeFormat('vi-VN', {
      dateStyle: 'medium',
      timeZone: 'UTC',
    }).format(new Date(`${value}T00:00:00Z`));
  }

  formatResetAt(value: string): string {
    return new Intl.DateTimeFormat('vi-VN', {
      dateStyle: 'medium',
      timeStyle: 'short',
      timeZone: 'UTC',
    }).format(new Date(value));
  }

  formatXp(value: number): string {
    return new Intl.NumberFormat('vi-VN').format(value);
  }

  private async loadAccount(): Promise<void> {
    this.profilePhase.set('loading');
    this.profileMessage.set(null);
    this.actionMessage.set(null);
    try {
      this.profile.set(await this.api.getProfile());
      this.profilePhase.set('ready');
      await this.fetchActivity(null, false);
    } catch (error) {
      this.activities.set([]);
      this.nextActivityCursor.set(null);
      this.activityPhase.set('idle');
      this.profileMessage.set(
        error instanceof CultivationApiError && error.code === 'CULTIVATION_NOT_ENABLED'
          ? 'Tính năng tu luyện chưa được bật trên máy chủ. Tiến trình và hoạt động của bạn sẽ không bị giả lập.'
          : this.errorMessage(error, 'Chưa tải được tiến trình tu luyện.'),
      );
      this.profilePhase.set(
        error instanceof CultivationApiError && error.code === 'CULTIVATION_NOT_ENABLED'
          ? 'unavailable'
          : 'error',
      );
    }
  }

  private async fetchActivity(cursor: string | null, append: boolean): Promise<void> {
    this.activityRetryCursor.set(cursor);
    this.activityMessage.set(null);
    this.activityPhase.set(append ? 'loading-more' : 'loading');
    try {
      const page: CultivationActivityPage = await this.api.getActivity(cursor ?? undefined);
      this.activities.update((current) => (append ? [...current, ...page.items] : page.items));
      this.nextActivityCursor.set(page.nextCursor);
      this.activityRetryCursor.set(null);
      this.activityPhase.set('ready');
    } catch (error) {
      this.activityMessage.set(this.errorMessage(error, 'Chưa tải được lịch sử theo ngày.'));
      this.activityPhase.set('error');
    }
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (!(error instanceof CultivationApiError)) return fallback;
    if (error.code === 'UNAUTHENTICATED' || error.status === 401) {
      return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại rồi thử tải tiến trình.';
    }
    if (error.code === 'POLICY_ACCEPTANCE_REQUIRED' || error.status === 403) {
      return 'Chính sách tài khoản đã thay đổi. Hãy xem lại chính sách rồi tải lại trang này.';
    }
    if (error.status === 0 || error.status === -1) {
      return 'Không kết nối được với máy chủ. Kiểm tra mạng rồi thử lại.';
    }
    return fallback;
  }
}
