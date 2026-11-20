package com.keplura.server.chat;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4b unit coverage for the stream observer: frame splitting across arbitrary
 * byte boundaries (incl. a UTF-8 multi-byte character split mid-stream), the
 * [DONE] latch, delta accumulation, malformed-frame tolerance and the model
 * tag capture. The relay contract itself is pinned by the HTTP suite.
 */
class SseTurnObserverTest {

    private static void feedAll(SseTurnObserver observer, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        // feed byte-by-byte: the worst-case split a real socket can produce
        for (int i = 0; i < bytes.length; i++) {
            observer.feed(bytes, i, 1);
        }
    }

    @Test
    void accumulatesDeltasAcrossArbitraryByteSplits() {
        SseTurnObserver o = new SseTurnObserver();
        feedAll(o, "event: message\ndata: {\"model\":\"m1\",\"choices\":[{\"delta\":{\"content\":\"hallo \"}}]}\n\n");
        feedAll(o, "data: {\"choices\":[{\"delta\":{\"content\":\"welt\"}}]}\n\ndata: [DONE]\n\n");
        o.finish();
        assertThat(o.answerText()).isEqualTo("hallo welt");
        assertThat(o.modelTag()).isEqualTo("m1");
        assertThat(o.sawDone()).isTrue();
        assertThat(o.overflowed()).isFalse();
    }

    @Test
    void multiByteCharacterSplitAcrossReadsSurvives() {
        SseTurnObserver o = new SseTurnObserver();
        String frame = "data: {\"choices\":[{\"delta\":{\"content\":\"ÄÖÜ – ok\"}}]}\n\n";
        byte[] bytes = frame.getBytes(StandardCharsets.UTF_8);
        int split = bytes.length / 2; // mid-character on purpose (UTF-8 seqs are wide)
        o.feed(bytes, 0, split);
        o.feed(bytes, split, bytes.length - split);
        o.finish();
        assertThat(o.answerText()).isEqualTo("ÄÖÜ – ok");
    }

    @Test
    void malformedAndForeignFramesAreIgnoredButStreamStillUsable() {
        SseTurnObserver o = new SseTurnObserver();
        feedAll(o, "data: not-json\n\n");
        feedAll(o, ": comment frame\n\n");
        feedAll(o, "data: {\"choices\":[]}\n\n");
        feedAll(o, "data: {\"choices\":[{\"delta\":{}}]}\n\n"); // no content field
        feedAll(o, "data: {\"choices\":[{\"delta\":{\"content\":\"X\"}}]}\n\n");
        o.finish();
        assertThat(o.answerText()).isEqualTo("X");
        assertThat(o.sawDone()).isFalse();
    }

    @Test
    void unterminatedLastLineIsFlushedByFinish() {
        SseTurnObserver o = new SseTurnObserver();
        byte[] bytes = "data: {\"choices\":[{\"delta\":{\"content\":\"tail\"}}]}\n\n"
                .getBytes(StandardCharsets.UTF_8);
        // cut off AFTER the last newline — nothing pending
        o.feed(bytes, 0, bytes.length);
        o.finish();
        assertThat(o.answerText()).isEqualTo("tail");

        SseTurnObserver o2 = new SseTurnObserver();
        String s = "data: {\"choices\":[{\"delta\":{\"content\":\"let\"}}]}\n";
        // feed everything but the final blank line: the frame line ends with \n
        // so it lands; then a partial follow-up line stays buffered until finish
        byte[] full = (s + "data: {\"choices\":[{\"delta\":{\"content\":\"zte\"}}]}").getBytes(StandardCharsets.UTF_8);
        o2.feed(full, 0, full.length);
        o2.finish();
        assertThat(o2.answerText()).isEqualTo("letzte");
    }

    @Test
    void nonStringContentAndDoneAfterTextStillLatches() {
        SseTurnObserver o = new SseTurnObserver();
        feedAll(o, "data: {\"choices\":[{\"delta\":{\"content\":null}}]}\n\n");
        feedAll(o, "data: [DONE]\n\n");
        o.finish();
        assertThat(o.answerText()).isEmpty();
        assertThat(o.sawDone()).isTrue();
    }
}
