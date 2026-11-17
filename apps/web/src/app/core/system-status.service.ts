import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { timeout } from 'rxjs';
import type { Subscription } from 'rxjs';
import type { ApiSystemStatus } from './models';

@Injectable({ providedIn: 'root' })
export class SystemStatusService {
  private readonly http = inject(HttpClient);

  readonly connection = signal<'checking' | 'online' | 'offline'>('checking');
  readonly status = signal<ApiSystemStatus | null>(null);
  private request?: Subscription;
  private readonly statusWaiters: Array<(status: ApiSystemStatus | null) => void> = [];

  constructor() {
    this.refresh();
  }

  refresh(): void {
    this.request?.unsubscribe();
    this.connection.set('checking');
    this.request = this.http
      .get<unknown>('/api/v1/system/status')
      .pipe(timeout(3_000))
      .subscribe({
        next: (payload) => {
          if (!isApiSystemStatus(payload)) {
            this.settled(null);
            return;
          }
          this.settled(payload);
        },
        error: () => {
          this.settled(null);
        },
      });
  }

  waitForStatus(): Promise<ApiSystemStatus | null> {
    if (this.connection() !== 'checking') return Promise.resolve(this.status());
    return new Promise((resolve) => this.statusWaiters.push(resolve));
  }

  private settled(status: ApiSystemStatus | null): void {
    this.status.set(status);
    this.connection.set(status ? 'online' : 'offline');
    for (const resolve of this.statusWaiters.splice(0)) resolve(status);
  }
}

function isApiSystemStatus(value: unknown): value is ApiSystemStatus {
  if (typeof value !== 'object' || value === null) return false;

  const status = value as Partial<ApiSystemStatus>;
  const integrations = status.integrations;
  const features = status.features;
  return (
    typeof status.apiVersion === 'string' &&
    typeof status.environment === 'string' &&
    typeof integrations?.googleOidcEnabled === 'boolean' &&
    typeof integrations.momoTestEnabled === 'boolean' &&
    typeof integrations.momoEnvironment === 'string' &&
    typeof features?.importProcessingEnabled === 'boolean' &&
    typeof features.cultivationEnabled === 'boolean' &&
    typeof features.commerceSandboxEnabled === 'boolean' &&
    (features.publicCatalogEnabled === undefined ||
      typeof features.publicCatalogEnabled === 'boolean') &&
    (features.publicationReleaseEnabled === undefined ||
      typeof features.publicationReleaseEnabled === 'boolean') &&
    typeof features.translationFakePreviewEnabled === 'boolean' &&
    (features.translationManualSubmissionEnabled === undefined ||
      typeof features.translationManualSubmissionEnabled === 'boolean')
  );
}
