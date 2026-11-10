package com.novelweb.identity.auth;

import java.net.URI;
import java.net.URISyntaxException;

final class SafeReturnPath {

    static final String DEFAULT_PATH = "/app/home";
    private static final int MAX_LENGTH = 512;

    private SafeReturnPath() {
    }

    static String sanitize(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() > MAX_LENGTH
                || candidate.charAt(0) != '/' || candidate.startsWith("//")
                || candidate.indexOf('\\') >= 0 || hasControlCharacters(candidate)) {
            return DEFAULT_PATH;
        }

        try {
            URI uri = new URI(candidate);
            String decodedPath = uri.getPath();
            if (uri.isAbsolute() || uri.getRawAuthority() != null
                    || decodedPath == null || !decodedPath.startsWith("/")
                    || decodedPath.startsWith("//") || decodedPath.indexOf('\\') >= 0
                    || hasDotSegment(decodedPath) || hasControlCharacters(decodedPath)) {
                return DEFAULT_PATH;
            }
            return candidate;
        } catch (URISyntaxException exception) {
            return DEFAULT_PATH;
        }
    }

    private static boolean hasDotSegment(String path) {
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasControlCharacters(String value) {
        return value.chars().anyMatch(character -> character <= 0x1f || character == 0x7f);
    }
}
