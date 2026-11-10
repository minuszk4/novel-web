import { inject } from '@angular/core';
import { CanActivateChildFn, Router } from '@angular/router';
import { PolicyOnboardingService } from './policy-onboarding.service';

export const policyConsentGuard: CanActivateChildFn = async (route, state) => {
  if (route.routeConfig?.path === 'setup') return true;

  const policies = inject(PolicyOnboardingService);
  const router = inject(Router);
  await policies.load();

  if (policies.status() === 'demo' || policies.status() === 'accepted') return true;
  return router.createUrlTree(['/app/setup'], { queryParams: { returnUrl: state.url } });
};
