package com.genai.first.ingeststatic.chunking;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Structure-aware chunking with overlap (Story 1, Task 1).
 * <ul>
 *     <li>The text is first cut into units: lines, and long lines into sentences, so a chunk never
 *     ends in the middle of a word and table rows stay on their own line.</li>
 *     <li>Units are packed into chunks of at most {@code chunkSize} tokens.</li>
 *     <li>Each new chunk starts with the last units of the previous one, up to {@code overlap} tokens.</li>
 *     <li>A unit longer than {@code chunkSize} is split on word boundaries.</li>
 * </ul>
 * Tokens are counted with the same tokenizer as Spring AI's TokenTextSplitter (cl100k_base).
 */
public class OverlappingTextSplitter {

    private static final Pattern LINE_BREAK = Pattern.compile("\\r?\\n");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?;:])\\s+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final Encoding encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    private final int chunkSize;
    private final int overlap;
    private final int minChunkLength;

    public OverlappingTextSplitter(int chunkSize, int overlap, int minChunkLength) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunk size must be > 0 tokens, was " + chunkSize);
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "chunk overlap must be >= 0 and smaller than the chunk size (" + chunkSize + "), was " + overlap);
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.minChunkLength = Math.max(0, minChunkLength);
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public int getOverlap() {
        return overlap;
    }

    /**
     * Splits one text (e.g. one PDF page) into overlapping chunks. Returns an empty list for blank text.
     */
    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        Deque<Unit> current = new ArrayDeque<>();
        int currentTokens = 0;

        for (Unit unit : toUnits(text)) {
            if (currentTokens + unit.tokens() > chunkSize && !current.isEmpty()) {
                addChunk(chunks, current);

                // keep the tail of the previous chunk as overlap, but leave room for the new unit
                Deque<Unit> tail = new ArrayDeque<>();
                int tailTokens = 0;
                var it = current.descendingIterator();
                while (it.hasNext()) {
                    Unit previous = it.next();
                    if (tailTokens + previous.tokens() > overlap
                            || tailTokens + previous.tokens() + unit.tokens() > chunkSize) {
                        break;
                    }
                    tail.addFirst(previous);
                    tailTokens += previous.tokens();
                }
                current = tail;
                currentTokens = tailTokens;
            }
            current.addLast(unit);
            currentTokens += unit.tokens();
        }
        addChunk(chunks, current);
        return chunks;
    }

    int countTokens(String text) {
        return encoding.countTokens(text);
    }

    private void addChunk(List<String> chunks, Deque<Unit> units) {
        if (units.isEmpty()) {
            return;
        }
        var sb = new StringBuilder();
        for (Unit unit : units) {
            if (!sb.isEmpty()) {
                sb.append(unit.newLine() ? "\n" : " ");
            }
            sb.append(unit.text());
        }
        var chunk = sb.toString().strip();
        if (chunk.length() >= minChunkLength && !chunk.isBlank()) {
            chunks.add(chunk);
        }
    }

    /**
     * Lines -> sentences -> word groups, each unit at most chunkSize tokens.
     */
    private List<Unit> toUnits(String text) {
        List<Unit> units = new ArrayList<>();
        for (String line : LINE_BREAK.split(text)) {
            var trimmedLine = line.strip();
            if (trimmedLine.isEmpty()) {
                continue;
            }
            boolean firstOfLine = true;
            for (String sentence : SENTENCE_END.split(trimmedLine)) {
                if (sentence.isBlank()) {
                    continue;
                }
                for (String piece : splitLongText(sentence.strip())) {
                    units.add(new Unit(piece, countTokens(piece), firstOfLine));
                    firstOfLine = false;
                }
            }
        }
        return units;
    }

    private List<String> splitLongText(String text) {
        if (countTokens(text) <= chunkSize) {
            return List.of(text);
        }
        List<String> pieces = new ArrayList<>();
        var sb = new StringBuilder();
        for (String word : WHITESPACE.split(text)) {
            var candidate = sb.isEmpty() ? word : sb + " " + word;
            if (countTokens(candidate) > chunkSize && !sb.isEmpty()) {
                pieces.add(sb.toString());
                sb.setLength(0);
                sb.append(word);
            } else {
                sb.setLength(0);
                sb.append(candidate);
            }
        }
        if (!sb.isEmpty()) {
            pieces.add(sb.toString());
        }
        return pieces;
    }

    /**
     * @param newLine true if the unit starts a new line in the original text (kept as "\n" when joining)
     */
    private record Unit(String text, int tokens, boolean newLine) {
    }
}
