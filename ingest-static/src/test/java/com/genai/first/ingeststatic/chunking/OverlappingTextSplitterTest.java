package com.genai.first.ingeststatic.chunking;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OverlappingTextSplitterTest {

    private static String numberedSentences(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> "Sentence number " + i + " describes one security rule of the handbook.")
                .collect(Collectors.joining("\n"));
    }

    @Test
    void blankTextGivesNoChunks() {
        var splitter = new OverlappingTextSplitter(50, 10, 0);
        assertThat(splitter.split(null)).isEmpty();
        assertThat(splitter.split("   \n  ")).isEmpty();
    }

    @Test
    void shortTextIsOneChunk() {
        var splitter = new OverlappingTextSplitter(400, 50, 0);
        var chunks = splitter.split("Report incidents in #security-alerts.\nInclude the time of detection.");
        assertThat(chunks).containsExactly("Report incidents in #security-alerts.\nInclude the time of detection.");
    }

    @Test
    void longTextIsSplitIntoChunksNotLargerThanTheChunkSize() {
        var splitter = new OverlappingTextSplitter(60, 15, 0);
        var chunks = splitter.split(numberedSentences(40));

        assertThat(chunks).hasSizeGreaterThan(1);
        chunks.forEach(chunk -> assertThat(splitter.countTokens(chunk)).isLessThanOrEqualTo(60 + 2));
    }

    @Test
    void consecutiveChunksOverlap() {
        var splitter = new OverlappingTextSplitter(60, 15, 0);
        var chunks = splitter.split(numberedSentences(40));

        for (int i = 1; i < chunks.size(); i++) {
            var firstLineOfNext = chunks.get(i).lines().findFirst().orElseThrow();
            assertThat(chunks.get(i - 1))
                    .as("chunk %d should start with text repeated from the end of chunk %d", i, i - 1)
                    .contains(firstLineOfNext);
        }
    }

    @Test
    void noOverlapWhenOverlapIsZero() {
        var splitter = new OverlappingTextSplitter(60, 0, 0);
        var chunks = splitter.split(numberedSentences(40));

        for (int i = 1; i < chunks.size(); i++) {
            var firstLineOfNext = chunks.get(i).lines().findFirst().orElseThrow();
            assertThat(chunks.get(i - 1)).doesNotContain(firstLineOfNext);
        }
    }

    @Test
    void neverCutsInsideAWord() {
        var text = numberedSentences(30) + "\n" + "averyveryverylongwordwithoutspaces ".repeat(80);
        var originalWords = new HashSet<>(Arrays.asList(text.split("\\s+")));
        var splitter = new OverlappingTextSplitter(50, 10, 0);

        for (var chunk : splitter.split(text)) {
            for (var word : chunk.split("\\s+")) {
                assertThat(originalWords).as("word '%s' must come from the original text", word).contains(word);
            }
        }
    }

    @Test
    void keepsTableRowsOnTheirOwnLine() {
        var rows = List.of(
                "Administrator Read / Write Full access Read / Write",
                "QA tester Read Staging only Anonymized only",
                "Contractor Read (assigned repos) No access No access");
        var splitter = new OverlappingTextSplitter(400, 50, 0);

        var chunk = splitter.split(String.join("\n", rows)).get(0);

        assertThat(chunk.lines().toList()).containsExactlyElementsOf(rows);
    }

    @Test
    void dropsChunksShorterThanTheMinimumLength() {
        var splitter = new OverlappingTextSplitter(400, 50, 20);
        assertThat(splitter.split("Page 3")).isEmpty();
    }

    @Test
    void rejectsOverlapNotSmallerThanTheChunkSize() {
        assertThatThrownBy(() -> new OverlappingTextSplitter(100, 100, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    void rejectsNonPositiveChunkSize() {
        assertThatThrownBy(() -> new OverlappingTextSplitter(0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunk size");
    }
}
