package com.novelweb.platform;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Records provider call time with fixed, bounded labels and no request or response data. */
@Component
@Profile("observability")
public final class ProviderCallMetrics {

  private static final ProviderCallMetrics NOOP = new ProviderCallMetrics(null);

  private final MeterRegistry registry;

  public ProviderCallMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public static ProviderCallMetrics noop() {
    return NOOP;
  }

  public void record(Provider provider, Operation operation, Outcome outcome, long elapsedNanos) {
    if (registry == null) return;
    try {
      Timer.builder("novel.provider.request.duration")
          .tag("provider", provider.metricName())
          .tag("operation", operation.metricName())
          .tag("outcome", outcome.metricName())
          .register(registry)
          .record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    } catch (RuntimeException ignored) {
      // Diagnostics must never change whether a bounded provider call is accepted.
    }
  }

  public enum Provider {
    FAKE_TRANSLATION("translation_fake"),
    MOMO_TEST("momo_test"),
    OTHER("other");

    private final String metricName;

    Provider(String metricName) {
      this.metricName = metricName;
    }

    public String metricName() {
      return metricName;
    }

    public static Provider forTranslationProvider(String providerId) {
      return "fake-local".equals(providerId) ? FAKE_TRANSLATION : OTHER;
    }
  }

  public enum Operation {
    TRANSLATE("translate"),
    CREATE_ORDER("create_order"),
    QUERY_ORDER("query_order");

    private final String metricName;

    Operation(String metricName) {
      this.metricName = metricName;
    }

    public String metricName() {
      return metricName;
    }
  }

  public enum Outcome {
    SUCCESS("success"),
    PENDING("pending"),
    DECLINED("declined"),
    TIMEOUT("timeout"),
    THROTTLED("throttled"),
    REJECTED("rejected"),
    TRANSPORT_ERROR("transport_error"),
    HTTP_ERROR("http_error"),
    INVALID_RESPONSE("invalid_response"),
    PROVIDER_FAILURE("provider_failure"),
    UNKNOWN("unknown"),
    ADAPTER_FAILURE("adapter_failure");

    private final String metricName;

    Outcome(String metricName) {
      this.metricName = metricName;
    }

    public String metricName() {
      return metricName;
    }
  }

  public static Outcome fromTranslationFailure(
      com.novelweb.translation.provider.TranslationProviderFailure failure) {
    Objects.requireNonNull(failure, "translation failure is required");
    return switch (failure) {
      case TIMEOUT -> Outcome.TIMEOUT;
      case RATE_LIMITED, QUOTA_EXCEEDED -> Outcome.THROTTLED;
      case UNSUPPORTED_LANGUAGE, INPUT_REJECTED, BILLING_DISABLED -> Outcome.REJECTED;
      case INVALID_RESPONSE -> Outcome.INVALID_RESPONSE;
      default -> Outcome.PROVIDER_FAILURE;
    };
  }
}
