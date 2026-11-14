package com.novelweb.translation.provider;

import java.util.Locale;
import java.util.regex.Pattern;

/** Canonical BCP-47 source/target language tags used at the provider boundary. */
public record TranslationLanguagePair(String sourceLanguage, String targetLanguage) {

  private static final Pattern LANGUAGE_TAG =
      Pattern.compile("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*");

  public TranslationLanguagePair {
    sourceLanguage = canonicalize(sourceLanguage);
    targetLanguage = canonicalize(targetLanguage);
    if (sourceLanguage.equalsIgnoreCase(targetLanguage)) {
      throw new IllegalArgumentException("Translation languages must be different");
    }
  }

  private static String canonicalize(String value) {
    if (value == null || !LANGUAGE_TAG.matcher(value).matches()) {
      throw new IllegalArgumentException("Language must be a valid BCP-47 tag");
    }
    String canonical = Locale.forLanguageTag(value).toLanguageTag();
    if (canonical.equals("und") || !canonical.equalsIgnoreCase(value)) {
      throw new IllegalArgumentException("Language must be a valid BCP-47 tag");
    }
    return canonical;
  }
}
