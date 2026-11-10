import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot } from '@angular/router';
import { signal } from '@angular/core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PolicyOnboardingService, PolicyOnboardingStatus } from './policy-onboarding.service';
import { policyConsentGuard } from './policy-consent.guard';

const returnUrl = '/app/library?sort=updated';
const redirectTree = { redirect: 'setup' };

function routeWithPath(path: string): ActivatedRouteSnapshot {
  return { routeConfig: { path } } as ActivatedRouteSnapshot;
}

function runGuard(status: PolicyOnboardingStatus) {
  const policies = { load: vi.fn(async () => undefined), status: signal(status) };
  const router = { createUrlTree: vi.fn(() => redirectTree) };
  TestBed.configureTestingModule({
    providers: [
      { provide: PolicyOnboardingService, useValue: policies },
      { provide: Router, useValue: router },
    ],
  });

  const result = TestBed.runInInjectionContext(() =>
    policyConsentGuard(routeWithPath('library'), { url: returnUrl } as RouterStateSnapshot),
  );
  return { result, policies, router };
}

afterEach(() => TestBed.resetTestingModule());

describe('policyConsentGuard', () => {
  it('allows the setup route without loading policy state', async () => {
    const result = await TestBed.runInInjectionContext(() =>
      policyConsentGuard(routeWithPath('setup'), { url: '/app/setup' } as RouterStateSnapshot),
    );

    expect(result).toBe(true);
  });

  it.each(['demo', 'accepted'] as const)(
    'allows the %s state after loading policy state',
    async (status) => {
      const { result, policies, router } = runGuard(status);

      await expect(result).resolves.toBe(true);
      expect(policies.load).toHaveBeenCalledOnce();
      expect(router.createUrlTree).not.toHaveBeenCalled();
    },
  );

  it.each(['idle', 'loading', 'pending', 'stale', 'unconfigured', 'error'] as const)(
    'redirects the %s state to setup and preserves the internal return URL',
    async (status) => {
      const { result, policies, router } = runGuard(status);

      await expect(result).resolves.toBe(redirectTree);
      expect(policies.load).toHaveBeenCalledOnce();
      expect(router.createUrlTree).toHaveBeenCalledWith(['/app/setup'], {
        queryParams: { returnUrl },
      });
    },
  );
});
