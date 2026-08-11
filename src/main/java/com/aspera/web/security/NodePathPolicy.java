package com.aspera.web.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Validation and comparison helpers for paths sent to the Aspera Node API.
 * These are virtual POSIX-style paths, not local filesystem paths.
 */
public final class NodePathPolicy {

    private static final int MAX_PATH_LENGTH = 4096;
    private static final int MAX_PATH_UTF8_BYTES = 2048;
    private static final int MAX_NAME_LENGTH = 255;

    private NodePathPolicy() {
    }

    public static String normalizeAbsolutePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Path is required.");
        }

        String path = rawPath;
        if (hasBoundaryWhitespace(path)) {
            throw new IllegalArgumentException("Leading or trailing whitespace is not allowed in paths.");
        }
        if (path.length() > MAX_PATH_LENGTH
                || path.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_UTF8_BYTES) {
            throw new IllegalArgumentException("Path exceeds the 2048-byte UTF-8 limit.");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Path must be absolute.");
        }
        rejectUnsafeCharacters(path);

        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Relative path segments are not allowed.");
            }
            if (hasBoundaryWhitespace(segment)) {
                throw new IllegalArgumentException("Leading or trailing whitespace is not allowed in path segments.");
            }
            if (segment.length() > MAX_NAME_LENGTH) {
                throw new IllegalArgumentException("A path segment is too long.");
            }
            segments.add(segment);
        }

        return segments.isEmpty() ? "/" : "/" + String.join("/", segments);
    }

    public static String join(String parentPath, String childName) {
        String parent = normalizeAbsolutePath(parentPath);
        if (childName == null || childName.isBlank()) {
            throw new IllegalArgumentException("Folder name is required.");
        }

        String name = childName;
        if (hasBoundaryWhitespace(name)) {
            throw new IllegalArgumentException("Leading or trailing whitespace is not allowed in folder names.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Folder name is too long.");
        }
        rejectUnsafeCharacters(name);
        if (name.contains("/") || ".".equals(name) || "..".equals(name)) {
            throw new IllegalArgumentException("Folder name contains an invalid path segment.");
        }

        return normalizeAbsolutePath(("/".equals(parent) ? "" : parent) + "/" + name);
    }

    public static boolean isSafeChildName(String childName) {
        if (childName == null || childName.isBlank() || childName.length() > MAX_NAME_LENGTH
                || ".".equals(childName) || "..".equals(childName)
                || childName.indexOf('/') >= 0 || childName.indexOf('\\') >= 0
                || hasBoundaryWhitespace(childName)) {
            return false;
        }
        for (int i = 0; i < childName.length(); i++) {
            char character = childName.charAt(i);
            if (character == 0 || Character.isISOControl(character)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isSameOrDescendant(String candidatePath, String allowedRoot) {
        String candidate = normalizeAbsolutePath(candidatePath);
        String root = normalizeAbsolutePath(allowedRoot);
        return "/".equals(root) || candidate.equals(root) || candidate.startsWith(root + "/");
    }

    public static boolean overlaps(String firstPath, String secondPath) {
        return isSameOrDescendant(firstPath, secondPath) || isSameOrDescendant(secondPath, firstPath);
    }

    private static void rejectUnsafeCharacters(String value) {
        if (value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Backslashes are not allowed in Node paths.");
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == 0 || Character.isISOControl(character)) {
                throw new IllegalArgumentException("Control characters are not allowed in Node paths.");
            }
        }
    }

    private static boolean hasBoundaryWhitespace(String value) {
        return !value.isEmpty()
                && (isWhitespace(value.charAt(0)) || isWhitespace(value.charAt(value.length() - 1)));
    }

    private static boolean isWhitespace(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character);
    }
}
