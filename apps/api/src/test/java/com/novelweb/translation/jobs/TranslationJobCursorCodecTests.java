package com.novelweb.translation.jobs;

import static com.novelweb.translation.jobs.TranslationJobCursorCodec.Position;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TranslationJobCursorCodecTests {

  private static final byte[] KEY =
      "01234567890123456789012345678901".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  private static final String ENCODED_KEY =
      Base64.getUrlEncoder().withoutPadding().encodeToString(KEY);

  @Test
  void roundTripsTimestampAndJobIdForTheSameActor() {
    UUID actorId = UUID.randomUUID();
    Position position =
        new Position(Instant.parse("2026-11-15T04:25:36.123456Z"), UUID.randomUUID());
    TranslationJobCursorCodec codec = codec();

    String cursor = codec.encode(actorId, position);

    assertThat(codec.decode(actorId, cursor)).isEqualTo(position);
    assertThat(cursor).doesNotContain(actorId.toString(), position.jobId().toString());
  }

  @Test
  void rejectsCrossActorTamperedMalformedAndOversizedCursors() {
    TranslationJobCursorCodec codec = codec();
    UUID actorId = UUID.randomUUID();
    String cursor = codec.encode(actorId, new Position(Instant.now(), UUID.randomUUID()));

    assertThatThrownBy(() -> codec.decode(UUID.randomUUID(), cursor))
        .isInstanceOf(TranslationJobCursorCodec.InvalidCursorException.class);
    assertThatThrownBy(() -> codec.decode(actorId, cursor + "x"))
        .isInstanceOf(TranslationJobCursorCodec.InvalidCursorException.class);
    assertThatThrownBy(() -> codec.decode(actorId, "not-a-cursor"))
        .isInstanceOf(TranslationJobCursorCodec.InvalidCursorException.class);
    assertThatThrownBy(() -> codec.decode(actorId, "x".repeat(257)))
        .isInstanceOf(TranslationJobCursorCodec.InvalidCursorException.class);
  }

  @Test
  void requiresAValidServerKeyBeforeIssuingOrReadingCursors() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new TranslationJobCursorCodec("not-base64url"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new TranslationJobCursorCodec(ENCODED_KEY.substring(1)));
    TranslationJobCursorCodec unconfigured = new TranslationJobCursorCodec("");
    assertThatThrownBy(() -> unconfigured.encode(UUID.randomUUID(), position()))
        .isInstanceOf(TranslationJobCursorCodec.SigningUnavailableException.class);
    assertThatThrownBy(() -> unconfigured.decode(UUID.randomUUID(), "x.y"))
        .isInstanceOf(TranslationJobCursorCodec.SigningUnavailableException.class);
  }

  private static TranslationJobCursorCodec codec() {
    return new TranslationJobCursorCodec(ENCODED_KEY);
  }

  private static Position position() {
    return new Position(Instant.now(), UUID.randomUUID());
  }
}
