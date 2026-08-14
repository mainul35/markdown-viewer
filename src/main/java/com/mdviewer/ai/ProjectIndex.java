package com.mdviewer.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What every file in a project declares, on one line each.
 *
 * <p>A scan reads a project a piece at a time, and a piece cannot see the others. That is
 * fine for facts that live in one file and useless for anything that spans two: the pass
 * holding a repository never meets the entity it loads. Carrying earlier passes' text
 * forward would solve it and does not fit - the whole reason for scanning is that the
 * project does not fit.
 *
 * <p>So carry names instead of text. This extracts declarations with regular expressions,
 * here, costing nothing and inventing nothing, and the result is small enough to send with
 * every pass: about 8000 characters for a 138-file project against 336000 of Java. A pass
 * cannot then read a file it was not given, but it always knows the file exists and what
 * is in it by name, which is the difference between "defined elsewhere in
 * entity/TenantUser.java" and silence.
 *
 * <p>Names only, and the prompt says so. An index is evidence that something is declared,
 * never evidence of what it does.
 */
public final class ProjectIndex {

    /** Types, and the enum constants that so often are the answer. */
    private static final Pattern JAVA_TYPE = Pattern.compile(
            "(?m)^\\s*(?:@\\w+\\s+)*(?:public|protected|private)?\\s*"
            + "(?:static\\s+|final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)*"
            + "(class|interface|enum|record)\\s+(\\w+)");

    /** A constant on its own line inside an enum - PLATFORM, ORGANIZATION, OWNER. */
    private static final Pattern JAVA_CONSTANT = Pattern.compile(
            "(?m)^\\s*([A-Z][A-Z0-9_]{2,})\\s*[,;(]");

    /** Where a controller answers, which is what "does it expose an API" means. */
    private static final Pattern JAVA_MAPPING = Pattern.compile(
            "@(?:Get|Post|Put|Delete|Patch|Request)Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"");

    private static final Pattern SQL_TABLE = Pattern.compile(
            "(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[\"`]?(\\w+)");

    private static final Pattern SQL_ALTER = Pattern.compile(
            "(?i)ALTER\\s+TABLE\\s+[\"`]?(\\w+)[\"`]?\\s+ADD\\s+(?:COLUMN\\s+)?[\"`]?(\\w+)");

    /** A top-level key in yaml or properties: the names a deployment can be configured by. */
    private static final Pattern CONFIG_KEY = Pattern.compile("(?m)^([a-zA-Z][\\w.-]*)\\s*[:=]");

    private ProjectIndex() {
    }

    /**
     * One line per file, within {@code maxChars}.
     *
     * <p>Detail is dropped rather than files: a map missing half the project would be a
     * map you cannot trust, while a map of names without their members is still a true
     * statement about where things live.
     */
    public static String of(Path root, List<Path> files, int maxChars) {
        for (int detail = 2; detail >= 0; detail--) {
            String map = build(root, files, detail);
            if (map.length() <= maxChars) {
                /* Say when detail was dropped. The first ceiling here was 300 characters
                   too low for this project, so the map fell all the way back to bare
                   filenames - which is exactly what it looks like when the extraction is
                   broken, and cost an hour finding out that it was not. */
                return detail == 2 ? map
                        : (detail == 1
                                ? "(declared names only; members did not fit)\n" + map
                                : "(file names only; the map of declarations did not fit)\n" + map);
            }
            if (detail == 0) {
                // Even names alone overflow. Say what was cut rather than cutting quietly.
                int keep = Math.max(0, maxChars - 200);
                int cut = map.lastIndexOf('\n', keep);
                return map.substring(0, Math.max(0, cut))
                        + "\n... (map truncated here; the project has " + files.size()
                        + " files and the rest are not listed)\n";
            }
        }
        return "";
    }

    private static String build(Path root, List<Path> files, int detail) {
        StringBuilder map = new StringBuilder();
        for (Path file : files) {
            String label;
            try {
                label = root.relativize(file).toString();
            } catch (IllegalArgumentException e) {
                label = file.toString();
            }
            List<String> symbols = detail == 0 ? List.of() : symbolsOf(file, detail);
            map.append(label);
            if (!symbols.isEmpty()) {
                map.append("  ::  ").append(String.join("; ", symbols));
            }
            map.append('\n');
        }
        return map.toString();
    }

    /** The declarations in one file. Never its contents, and never an interpretation. */
    static List<String> symbolsOf(Path file, int detail) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String text;
        try {
            // A generated template runs to 48000 characters and declares nothing; reading
            // the head of a file is enough to find what it declares in every case here.
            text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.length() > 200_000) {
                text = text.substring(0, 200_000);
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }

        if (name.endsWith(".java") || name.endsWith(".kt")) {
            return javaSymbols(text, detail);
        }
        if (name.endsWith(".sql")) {
            return sqlSymbols(text);
        }
        if (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties")) {
            return capped(distinct(matches(CONFIG_KEY, text, 1)), 12);
        }
        return List.of();
    }

    private static List<String> javaSymbols(String text, int detail) {
        List<String> out = new ArrayList<>();
        Matcher types = JAVA_TYPE.matcher(text);
        boolean hasEnum = false;
        while (types.find()) {
            out.add(types.group(1) + " " + types.group(2));
            hasEnum |= types.group(1).equals("enum");
        }
        if (detail >= 2) {
            if (hasEnum) {
                // Only for a file that declares one: ALL-CAPS elsewhere is a constant field,
                // and listing every one of those buries the names that matter.
                List<String> constants = capped(distinct(matches(JAVA_CONSTANT, text, 1)), 16);
                if (!constants.isEmpty()) {
                    out.add("values[" + String.join(",", constants) + "]");
                }
            }
            List<String> paths = capped(distinct(matches(JAVA_MAPPING, text, 1)), 10);
            if (!paths.isEmpty()) {
                out.add("endpoints[" + String.join(",", paths) + "]");
            }
        }
        return out;
    }

    private static List<String> sqlSymbols(String text) {
        List<String> out = new ArrayList<>();
        for (String table : distinct(matches(SQL_TABLE, text, 1))) {
            out.add("table " + table);
        }
        Matcher alter = SQL_ALTER.matcher(text);
        Set<String> added = new LinkedHashSet<>();
        while (alter.find()) {
            added.add(alter.group(1) + "." + alter.group(2));
        }
        for (String column : capped(new ArrayList<>(added), 12)) {
            out.add("adds " + column);
        }
        return out;
    }

    private static List<String> matches(Pattern pattern, String text, int group) {
        List<String> out = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(group);
            if (value != null && !value.isBlank()) {
                out.add(value);
            }
        }
        return out;
    }

    private static List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private static List<String> capped(List<String> values, int limit) {
        if (values.size() <= limit) {
            return values;
        }
        List<String> out = new ArrayList<>(values.subList(0, limit));
        out.add("+" + (values.size() - limit) + " more");
        return out;
    }
}
