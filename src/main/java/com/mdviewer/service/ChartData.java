package com.mdviewer.service;

import java.util.ArrayList;
import java.util.List;

/**
 * A chart fence as a grid of values plus its settings, so it can be edited as a form
 * rather than as text.
 *
 * <p>Reading a fence and writing one are deliberately split. The reading is done by
 * mdchart.js and arrives here already taken apart - there is one parser for this syntax
 * and it is the one that also draws the chart, so an editor can never disagree with the
 * picture about what a fence says. Writing is this class's own job: producing the text is
 * simple, and doing it here keeps the editor off the page entirely.
 *
 * <p>Values are strings, not numbers. Someone editing a chart types "1,200" and "12.5%",
 * and rounding those through a double on the way in and out would rewrite what they wrote.
 */
public final class ChartData {

    /** One row: its label, then one value per column. */
    public record Row(String label, List<String> values) { }

    private String type;
    private String title;
    private String unit;
    private String delta;
    /** Column headings. Empty when the chart is a plain list of label-and-value rows. */
    private final List<String> categories = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();

    private ChartData() {
    }

    public String type() {
        return type;
    }

    public String title() {
        return title;
    }

    public String unit() {
        return unit;
    }

    public String delta() {
        return delta;
    }

    public List<String> categories() {
        return categories;
    }

    public List<Row> rows() {
        return rows;
    }

    /**
     * Reads the model that {@code MdChart.model()} produced.
     *
     * <p>Line-based and tab-separated: a label may contain a comma, and everything here is
     * free text the reader typed.
     */
    public static ChartData fromModel(String model) {
        ChartData data = new ChartData();
        data.type = "";
        data.title = "";
        data.unit = "";
        data.delta = "";
        if (model == null) {
            return data;
        }
        for (String line : model.split("\n")) {
            int equals = line.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = line.substring(0, equals);
            String value = line.substring(equals + 1);
            switch (key) {
                case "type" -> data.type = value.trim();
                case "title" -> data.title = value;
                case "unit" -> data.unit = value;
                case "delta" -> data.delta = value;
                case "categories" -> {
                    for (String category : split(value)) {
                        data.categories.add(category);
                    }
                }
                case "row" -> {
                    List<String> parts = split(value);
                    if (!parts.isEmpty()) {
                        data.rows.add(new Row(parts.get(0),
                                new ArrayList<>(parts.subList(1, parts.size()))));
                    }
                }
                default -> { }
            }
        }
        return data;
    }

    /** Tab-separated, keeping empty fields: a blank cell is a real value here. */
    private static List<String> split(String value) {
        List<String> out = new ArrayList<>();
        if (value.isEmpty()) {
            return out;
        }
        int from = 0;
        for (int at = value.indexOf('\t'); at >= 0; at = value.indexOf('\t', from)) {
            out.add(value.substring(from, at));
            from = at + 1;
        }
        out.add(value.substring(from));
        return out;
    }

    /**
     * Builds a chart fence from a grid.
     *
     * <p>The number of value columns decides the syntax, because it decides what the data
     * means. One column is a list of items, where the row label is the category and there
     * is nothing for an {@code x:} to name. More than one is a series per row plotted
     * against the columns, which is exactly what {@code x:} is for. Writing whichever one
     * matches means the fence still reads as the thing it is, to a person as well as to
     * the renderer.
     *
     * <p>Labels are padded to a common width so the source stays a readable column of
     * numbers - the syntax was built to survive being read unrendered, and an editor that
     * writes it back as ragged text takes that away.
     */
    public static String toFence(String type, String title, String unit, String delta,
                                 List<String> categories, List<Row> rows) {
        StringBuilder out = new StringBuilder("```chart\n");
        out.append("type: ").append(type == null || type.isBlank() ? "bar" : type.trim())
                .append('\n');
        appendSetting(out, "title", title);
        appendSetting(out, "unit", unit);
        // Only a stat tile draws a change line; carrying it on a bar chart would leave a
        // setting in the source that nothing reads.
        if ("stat".equals(type)) {
            appendSetting(out, "delta", delta);
        }
        if (categories.size() > 1) {
            appendSetting(out, "x", String.join(", ", categories));
        }
        out.append("---\n");

        int widest = 0;
        for (Row row : rows) {
            widest = Math.max(widest, row.label().length());
        }
        for (Row row : rows) {
            out.append(pad(row.label(), widest)).append(" | ")
                    .append(String.join(", ", row.values())).append('\n');
        }
        out.append("```");
        return out.toString();
    }

    private static void appendSetting(StringBuilder out, String key, String value) {
        if (value != null && !value.isBlank()) {
            out.append(key).append(": ").append(value.trim()).append('\n');
        }
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }
}
