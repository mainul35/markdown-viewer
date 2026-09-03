package com.mdviewer.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reading the server's replies.
 *
 * <p>This is not a JSON parser and does not try to be one, but it is the only thing
 * standing between a plan response and what the sync then does to somebody's documents -
 * so what it can and cannot survive is worth stating rather than assuming.
 *
 * <p>The brace-counting cases are here because it once could not survive them. Counting
 * brackets without knowing which are inside strings meant a document named
 * {@code summary].md} silently emptied the whole change list, and an empty change list
 * reads as "nothing to do" - while a commit built from it would have told the server every
 * document it holds and this machine has not seen was deleted.
 */
class CloudClientJsonTest {

    @Nested
    @DisplayName("finding a value")
    class Values {

        @Test
        void readsStringsAndNumbers() {
            String json = "{\"id\":\"abc-123\",\"revision\":42,\"fitsInQuota\":true}";
            assertEquals("abc-123", CloudClient.string(json, "id"));
            assertEquals(42, CloudClient.number(json, "revision"));
            assertEquals(true, CloudClient.bool(json, "fitsInQuota"));
        }

        @Test
        @DisplayName("a missing field is absent, not an error")
        void missingFieldIsEmpty() {
            assertEquals("", CloudClient.string("{\"a\":\"b\"}", "nothere"));
            assertEquals(0, CloudClient.number("{\"a\":\"b\"}", "nothere"));
        }

        @Test
        @DisplayName("an escaped quote inside a value does not end it")
        void escapedQuote() {
            assertEquals("say \"hi\"", CloudClient.string("{\"m\":\"say \\\"hi\\\"\"}", "m"));
        }

        @Test
        @DisplayName("a negative number is negative")
        void negativeNumber() {
            assertEquals(-5, CloudClient.number("{\"delta\":-5}", "delta"));
        }
    }

    @Nested
    @DisplayName("splitting an array of changes")
    class Arrays {

        private String planWith(String... paths) {
            StringBuilder json = new StringBuilder("{\"revision\":7,\"changes\":[");
            for (int i = 0; i < paths.length; i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append("{\"path\":\"").append(paths[i])
                        .append("\",\"action\":\"DOWNLOAD\",\"remoteHash\":\"h").append(i)
                        .append("\",\"bytes\":10}");
            }
            return json.append("],\"blobsToUpload\":[]}").toString();
        }

        private List<String> changesIn(String json) {
            return CloudClient.objects(CloudClient.section(json, "changes"));
        }

        @Test
        void ordinaryPaths() {
            List<String> changes = changesIn(planWith("a.md", "notes/b.md", "c.md"));
            assertEquals(3, changes.size());
            assertEquals("notes/b.md", CloudClient.string(changes.get(1), "path"));
        }

        /**
         * A closing bracket in a filename used to end the array where it appeared, so every
         * change was lost - including the first, which came before it.
         */
        @Test
        @DisplayName("a ] in a document's name does not end the change list")
        void closingBracketInAPath() {
            List<String> changes = changesIn(planWith("summary].md", "second.md", "third.md"));
            assertEquals(3, changes.size());
            assertEquals("summary].md", CloudClient.string(changes.get(0), "path"));
            assertEquals("third.md", CloudClient.string(changes.get(2), "path"));
        }

        @Test
        @DisplayName("a [ in a document's name does not swallow the rest")
        void openingBracketInAPath() {
            List<String> changes = changesIn(planWith("a.md", "[draft.md", "c.md"));
            assertEquals(3, changes.size());
            assertEquals("[draft.md", CloudClient.string(changes.get(1), "path"));
        }

        /** A brace closed one change early and left the next one with no fields at all. */
        @Test
        @DisplayName("a } in a document's name does not end that change")
        void closingBraceInAPath() {
            List<String> changes = changesIn(planWith("a.md", "drafts/v1}.md", "c.md"));
            assertEquals(3, changes.size());
            assertEquals("drafts/v1}.md", CloudClient.string(changes.get(1), "path"));
            assertEquals("h1", CloudClient.string(changes.get(1), "remoteHash"));
        }

        @Test
        @DisplayName("brackets in a balanced pair are still just text")
        void balancedBracketsInAPath() {
            List<String> changes = changesIn(planWith("[2024] plan.md", "b.md"));
            assertEquals(2, changes.size());
            assertEquals("[2024] plan.md", CloudClient.string(changes.get(0), "path"));
        }

        /**
         * The reason is a sentence written by the server for a person to read, so it is the
         * field most likely to contain punctuation nobody planned for.
         */
        @Test
        @DisplayName("punctuation in a reason does not break the change it belongs to")
        void punctuationInAReason() {
            String json = "{\"changes\":["
                    + "{\"path\":\"a.md\",\"action\":\"CONFLICT\",\"reason\":\"changed here [and] there {both}\"},"
                    + "{\"path\":\"b.md\",\"action\":\"DOWNLOAD\",\"reason\":\"new remotely\"}"
                    + "]}";
            List<String> changes = CloudClient.objects(CloudClient.section(json, "changes"));
            assertEquals(2, changes.size());
            assertEquals("changed here [and] there {both}", CloudClient.string(changes.get(0), "reason"));
            assertEquals("b.md", CloudClient.string(changes.get(1), "path"));
        }

        @Test
        @DisplayName("an escaped quote in a path does not end the string")
        void escapedQuoteInAPath() {
            String json = "{\"changes\":[{\"path\":\"od\\\"d.md\",\"action\":\"DOWNLOAD\"},"
                    + "{\"path\":\"b.md\",\"action\":\"DOWNLOAD\"}]}";
            List<String> changes = CloudClient.objects(CloudClient.section(json, "changes"));
            assertEquals(2, changes.size());
        }

        @Test
        void emptyArrayIsEmpty() {
            assertEquals(0, changesIn(planWith()).size());
        }

        @Test
        @DisplayName("a list of plain strings survives punctuation too")
        void stringArray() {
            List<String> hashes = CloudClient.strings(
                    CloudClient.section("{\"blobsToUpload\":[\"aaa\",\"bbb\"]}", "blobsToUpload"));
            assertEquals(List.of("aaa", "bbb"), hashes);
        }
    }
}
