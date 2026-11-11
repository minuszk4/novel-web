package com.novelweb.library.reader;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class ReaderPreferencesService {

  private static final Set<String> THEMES = Set.of("light", "sepia", "dark");
  private static final Set<String> FONT_FAMILIES = Set.of("serif", "sans");

  private final ReaderPreferencesRepository repository;

  public ReaderPreferencesService(ReaderPreferencesRepository repository) {
    this.repository = repository;
  }

  public ReaderPreferencesRepository.PreferencesRecord get(UUID actorUserId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    return repository.find(actorUserId);
  }

  public ReaderPreferencesRepository.PreferencesRecord save(
      UUID actorUserId, SavePreferencesRequest request) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    if (request == null) {
      throw new InvalidPreferencesException("All reader preference fields are required");
    }
    if (request.hasUnknownFields()) {
      throw new InvalidPreferencesException("Reader preferences contain an unsupported field");
    }
    if (request.schemaVersion() == null
        || request.theme() == null
        || request.fontFamily() == null
        || request.fontSize() == null
        || request.lineHeight() == null
        || request.contentWidth() == null) {
      throw new InvalidPreferencesException("All reader preference fields are required");
    }
    if (request.schemaVersion() != 1) {
      throw new InvalidPreferencesException("schemaVersion must be 1");
    }
    if (!THEMES.contains(request.theme())) {
      throw new InvalidPreferencesException("theme must be light, sepia, or dark");
    }
    if (!FONT_FAMILIES.contains(request.fontFamily())) {
      throw new InvalidPreferencesException("fontFamily must be serif or sans");
    }
    if (request.fontSize() < 16 || request.fontSize() > 28) {
      throw new InvalidPreferencesException("fontSize must be between 16 and 28");
    }
    if (!Double.isFinite(request.lineHeight())
        || request.lineHeight() < 1.2
        || request.lineHeight() > 2.2) {
      throw new InvalidPreferencesException("lineHeight must be between 1.2 and 2.2");
    }
    if (request.contentWidth() < 560 || request.contentWidth() > 900) {
      throw new InvalidPreferencesException("contentWidth must be between 560 and 900");
    }
    return repository.save(
        actorUserId,
        new ReaderPreferencesRepository.PreferencesRecord(
            request.schemaVersion(),
            request.theme(),
            request.fontFamily(),
            request.fontSize(),
            request.lineHeight(),
            request.contentWidth()));
  }

  public static final class SavePreferencesRequest {
    private Integer schemaVersion;
    private String theme;
    private String fontFamily;
    private Integer fontSize;
    private Double lineHeight;
    private Integer contentWidth;
    private boolean unknownFields;

    public SavePreferencesRequest() {}

    public SavePreferencesRequest(
        Integer schemaVersion,
        String theme,
        String fontFamily,
        Integer fontSize,
        Double lineHeight,
        Integer contentWidth) {
      this.schemaVersion = schemaVersion;
      this.theme = theme;
      this.fontFamily = fontFamily;
      this.fontSize = fontSize;
      this.lineHeight = lineHeight;
      this.contentWidth = contentWidth;
    }

    public Integer schemaVersion() {
      return schemaVersion;
    }

    public String theme() {
      return theme;
    }

    public String fontFamily() {
      return fontFamily;
    }

    public Integer fontSize() {
      return fontSize;
    }

    public Double lineHeight() {
      return lineHeight;
    }

    public Integer contentWidth() {
      return contentWidth;
    }

    boolean hasUnknownFields() {
      return unknownFields;
    }

    public void setSchemaVersion(Integer schemaVersion) {
      this.schemaVersion = schemaVersion;
    }

    public void setTheme(String theme) {
      this.theme = theme;
    }

    public void setFontFamily(String fontFamily) {
      this.fontFamily = fontFamily;
    }

    public void setFontSize(Integer fontSize) {
      this.fontSize = fontSize;
    }

    public void setLineHeight(Double lineHeight) {
      this.lineHeight = lineHeight;
    }

    public void setContentWidth(Integer contentWidth) {
      this.contentWidth = contentWidth;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object ignoredValue) {
      unknownFields = true;
    }
  }

  public static final class InvalidPreferencesException extends RuntimeException {
    public InvalidPreferencesException(String message) {
      super(message);
    }
  }
}
