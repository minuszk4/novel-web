import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { SystemStatusService } from './system-status.service';

const validStatus = {
  apiVersion: 'v1',
  environment: 'local',
  integrations: {
    googleOidcEnabled: false,
    momoTestEnabled: false,
    momoEnvironment: 'TEST',
  },
  features: {
    importProcessingEnabled: false,
    cultivationEnabled: false,
    commerceSandboxEnabled: false,
  },
};

describe('SystemStatusService', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('validates the server status and transitions to online', () => {
    const service = TestBed.inject(SystemStatusService);

    expect(service.connection()).toBe('checking');
    http.expectOne('/api/v1/system/status').flush(validStatus);

    expect(service.connection()).toBe('online');
    expect(service.status()).toEqual(validStatus);
  });

  it('treats a malformed response as offline and clears status', () => {
    const service = TestBed.inject(SystemStatusService);

    http.expectOne('/api/v1/system/status').flush({
      ...validStatus,
      features: { importProcessingEnabled: true },
    });

    expect(service.connection()).toBe('offline');
    expect(service.status()).toBeNull();
  });

  it('treats an API error as offline and supports an explicit retry', () => {
    const service = TestBed.inject(SystemStatusService);
    http.expectOne('/api/v1/system/status').flush('unavailable', {
      status: 503,
      statusText: 'Service Unavailable',
    });

    expect(service.connection()).toBe('offline');
    expect(service.status()).toBeNull();

    service.refresh();
    expect(service.connection()).toBe('checking');
    http.expectOne('/api/v1/system/status').flush(validStatus);

    expect(service.connection()).toBe('online');
    expect(service.status()).toEqual(validStatus);
  });
});
