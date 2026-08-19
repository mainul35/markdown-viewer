package com.mdviewer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writing a chart fence from what somebody typed into the dialog.
 *
 * <p>The dialog exists so that nobody has to get fence syntax right by hand. That makes
 * this the code which has to get it right, whatever arrives - and what arrives is typed and
 * pasted text, which is to say anything at all.
 */
class ChartDataTest {

    private static ChartData.Row row(String label, String... values) {
        return new ChartData.Row(label, List.of(values));
    }

    /** A fence is well formed when the only backtick runs in it are its own two. */
    private static int fenceMarkers(String fence) {
        return fence.split("```", -1).length - 1;
    }

    @Test
    @DisplayName("an ordinary chart reads the way it was written")
    void ordinary() {
        String fence = ChartData.toFence("bar", "Quarterly revenue", "USD", "",
                List.of(), List.of(row("First", "412"), row("Second", "208")));

        assertTrue(fence.startsWith("```chart\ntype: bar\n"));
        assertTrue(fence.contains("title: Quarterly revenue\n"));
        assertTrue(fence.contains("unit: USD\n"));
        assertTrue(fence.contains("First  | 412\n"));
        assertTrue(fence.endsWith("```"));
        assertEquals(2, fenceMarkers(fence));
    }

    /**
     * A title pasted from somewhere else brings its line breaks with it. That used to end
     * the fence at the break, leaving the rest of the chart in the document as ordinary
     * text with a stray run of backticks after it - from a dialog whose whole purpose is
     * that the reader never has to think about fences.
     */
    @Test
    @DisplayName("a pasted multi-line title cannot break the fence")
    void multiLineTitle() {
        String fence = ChartData.toFence("bar", "Quarterly\n```\n# not a chart", "", "",
                List.of(), List.of(row("Total", "10")));

        assertEquals(2, fenceMarkers(fence), "the fence should still open and close once:\n" + fence);
        assertTrue(fence.contains("title: Quarterly ' ' # not a chart")
                        || fence.contains("title: Quarterly"),
                "the title should survive on one line:\n" + fence);
        assertFalse(fence.contains("\ntitle: Quarterly\n```\n"), "the fence closed early:\n" + fence);
    }

    @Test
    @DisplayName("a label containing a bar does not become two columns")
    void barInALabel() {
        String fence = ChartData.toFence("bar", "T", "", "",
                List.of(), List.of(row("Total | secret", "10")));

        // mdchart splits a data row at its first bar, so a second one silently truncates
        // the label and moves the rest into the values.
        String dataLine = fence.lines().filter(line -> line.contains("10")).findFirst().orElse("");
        assertEquals(1, dataLine.chars().filter(c -> c == '|').count(),
                "a data row should have exactly one bar: " + dataLine);
    }

    @Test
    @DisplayName("a stat keeps its change line even when the type arrives untidy")
    void statDeltaSurvivesUntidyType() {
        String fence = ChartData.toFence(" Stat ", "T", "files", "+12% vs last week",
                List.of(), List.of(row("Total", "1284")));

        assertTrue(fence.contains("type: stat\n"), fence);
        assertTrue(fence.contains("delta: +12% vs last week\n"),
                "the reader typed a change line and it was dropped:\n" + fence);
    }

    @Test
    @DisplayName("a bar chart does not carry a change line nothing reads")
    void deltaOnlyOnStat() {
        String fence = ChartData.toFence("bar", "T", "", "+12%",
                List.of(), List.of(row("Total", "10")));
        assertFalse(fence.contains("delta:"), fence);
    }

    @Test
    @DisplayName("several value columns are written against an x axis")
    void multipleSeries() {
        String fence = ChartData.toFence("column", "T", "", "",
                List.of("Mon", "Tue"), List.of(row("First", "1", "2"), row("Second", "3", "4")));

        assertTrue(fence.contains("x: Mon, Tue\n"), fence);
        assertTrue(fence.contains("First  | 1, 2\n"), fence);
    }

    @Test
    @DisplayName("one value column needs no x axis")
    void singleSeriesHasNoAxis() {
        String fence = ChartData.toFence("bar", "T", "", "",
                List.of("Only"), List.of(row("First", "1")));
        assertFalse(fence.contains("x:"), fence);
    }

    @Test
    @DisplayName("an empty title is left out rather than written blank")
    void emptySettingsAreOmitted() {
        String fence = ChartData.toFence("bar", "", "  ", null,
                List.of(), List.of(row("First", "1")));
        assertFalse(fence.contains("title:"), fence);
        assertFalse(fence.contains("unit:"), fence);
    }

    @Test
    @DisplayName("no type at all is still a chart")
    void missingTypeDefaults() {
        String fence = ChartData.toFence(null, "T", "", "",
                List.of(), List.of(row("First", "1")));
        assertTrue(fence.startsWith("```chart\ntype: bar\n"), fence);
    }
}
