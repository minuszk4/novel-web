package com.novelweb.library.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.novelweb.library.imports.ImportCreationService.CreateCommand;
import com.novelweb.library.imports.ImportCreationService.Outcome;
import com.novelweb.library.imports.ImportCreationService.SourceDescriptor;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImportCreationServiceTests {

  private static final long MAX_BYTES = 1024;
  private static final UUID LIBRARY_ID = UUID.randomUUID();
  private static final UUID RECEIPT_ID = UUID.randomUUID();

  @Test
  void stripsClientPathAndAllowsSupportedEpubMimeHint() {
    var validation =
        ImportCreationService.validate(
            command("C:\\fakepath\\story.EPUB", "application/epub+zip", 512L, true, "v1"),
            MAX_BYTES);

    assertEquals(Outcome.CREATED, validation.outcome());
    assertEquals("story.EPUB", validation.normalized().filename());
    assertEquals("application/epub+zip", validation.normalized().contentType());
  }

  @Test
  void rejectsMissingOrFalseRightsConfirmationBeforeDatabaseWork() {
    assertEquals(
        Outcome.RIGHTS_CONFIRMATION_REQUIRED,
        ImportCreationService.validate(
                command("story.txt", "text/plain", 12L, false, "v1"), MAX_BYTES)
            .outcome());
    assertEquals(
        Outcome.RIGHTS_CONFIRMATION_REQUIRED,
        ImportCreationService.validate(
                command("story.txt", "text/plain", 12L, null, "v1"), MAX_BYTES)
            .outcome());
  }

  @Test
  void rejectsUnsupportedExtensionAndMismatchedMimeHint() {
    assertEquals(
        Outcome.FILE_METADATA_INVALID,
        ImportCreationService.validate(
                command("story.exe", "application/octet-stream", 12L, true, "v1"), MAX_BYTES)
            .outcome());
    assertEquals(
        Outcome.FILE_METADATA_INVALID,
        ImportCreationService.validate(
                command("story.epub", "text/plain", 12L, true, "v1"), MAX_BYTES)
            .outcome());
    assertEquals(
        Outcome.FILE_METADATA_INVALID,
        ImportCreationService.validate(
                command("story.html", "text/plain", 12L, true, "v1"), MAX_BYTES)
            .outcome());
    assertEquals(
        Outcome.FILE_METADATA_INVALID,
        ImportCreationService.validate(
                command("story.txt", "text/html", 12L, true, "v1"), MAX_BYTES)
            .outcome());
  }

  @Test
  void rejectsMissingZeroAndOversizedDeclaredBytes() {
    assertEquals(
        Outcome.FILE_METADATA_INVALID,
        ImportCreationService.validate(
                command("story.txt", "text/plain", null, true, "v1"), MAX_BYTES)
            .outcome());
    assertEquals(
        Outcome.FILE_METADATA_INVALID,
        ImportCreationService.validate(
                command("story.txt", "text/plain", 0L, true, "v1"), MAX_BYTES)
            .outcome());
    assertEquals(
        Outcome.FILE_METADATA_INVALID,
        ImportCreationService.validate(
                command("story.txt", "text/plain", MAX_BYTES + 1, true, "v1"), MAX_BYTES)
            .outcome());
  }

  @Test
  void rejectsPathOnlyControlAndUnknownVersionInputs() {
    var pathOnly =
        ImportCreationService.validate(
            command("C:\\temp\\", "text/plain", 12L, true, "v1"), MAX_BYTES);
    var control =
        ImportCreationService.validate(
            command("story\n.txt", "text/plain", 12L, true, "v1"), MAX_BYTES);
    var badVersion =
        ImportCreationService.validate(
            command("story.txt", "text/plain", 12L, true, " bad version "), MAX_BYTES);

    assertEquals(Outcome.FILE_METADATA_INVALID, pathOnly.outcome());
    assertEquals(Outcome.FILE_METADATA_INVALID, control.outcome());
    assertEquals(Outcome.RIGHTS_CONFIRMATION_REQUIRED, badVersion.outcome());
    assertNull(pathOnly.normalized());
  }

  private static CreateCommand command(
      String filename, String contentType, Long size, Boolean confirmed, String version) {
    return new CreateCommand(
        LIBRARY_ID,
        RECEIPT_ID,
        version,
        confirmed,
        new SourceDescriptor("upload", filename, contentType, size));
  }
}
