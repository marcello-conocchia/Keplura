package com.keplura.server.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * M3d: the local hash embedding must be bit-deterministic (stored vectors
 * can be trusted across restarts and CI machines), dimension-stable, and
 * behave sensibly (lexical overlap ⇒ high cosine; disjoint words ⇒ low). The
 * codec must round-trip through the canonical form that pgvector consumes.
 */
class EmbeddingDeterminismTest {

    @Test
    void sameTextAlwaysYieldsTheSameVector() {
        LocalHashEmbeddingService a = new LocalHashEmbeddingService(64);
        LocalHashEmbeddingService b = new LocalHashEmbeddingService(64);
        String text = "Keplura Baupläne für die Wissensdatenbank — 2026 🚀";
        assertThat(a.embed(text)).containsExactly(b.embed(text));
        assertThat(a.embed(text)).containsExactly(a.embed(text)); // repeated calls stable
    }

    @Test
    void dimensionIsHonouredAndVectorsAreNormalised() {
        LocalHashEmbeddingService svc = new LocalHashEmbeddingService(128);
        float[] v = svc.embed("some meaningful text about things");
        assertThat(v).hasSize(128);
        double norm = 0;
        for (float f : v) {
            norm += (double) f * f;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(1e-4));
    }

    @Test
    void lexicalOverlapScoresHighAndDisjointTextLow() {
        LocalHashEmbeddingService svc = new LocalHashEmbeddingService(256);
        float[] doc = svc.embed("der bauplan enthält genau achtundvierzig specificationen module");
        float[] near = svc.embed("bauplan specificationen module");
        float[] far = svc.embed("apfel birne kirsche traube zitrone");
        assertThat(Similarity.cosine(doc, near)).isGreaterThan(0.5);
        assertThat(Similarity.cosine(doc, far)).isLessThan(0.2);
    }

    @Test
    void emptyTextEmbedsToZeroVectorWithoutBlowingUp() {
        LocalHashEmbeddingService svc = new LocalHashEmbeddingService(64);
        float[] v = svc.embed("");
        assertThat(v).hasSize(64);
        assertThat(Similarity.cosine(v, v)).isZero(); // zero-norm guard
    }

    @Test
    void codecRoundTripsBitExactlyThroughTheCanonicalForm() {
        LocalHashEmbeddingService svc = new LocalHashEmbeddingService(64);
        float[] v = svc.embed("kanonische kodierung roundtrip");
        String encoded = EmbeddingCodec.encode(v);
        assertThat(encoded).matches("^\\[-?\\d+\\.?\\d*(E[-+]?\\d+)?(,-?\\d+\\.?\\d*(E[-+]?\\d+)?)*\\]$");
        assertThat(EmbeddingCodec.decode(encoded)).containsExactly(v);
    }

    @Test
    void codecRejectsNonFiniteComponentsAndGarbageInput() {
        assertThatThrownBy(() -> EmbeddingCodec.encode(new float[]{1f, Float.NaN}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmbeddingCodec.decode("1,2,3"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void similarityRejectsDimensionMismatch() {
        assertThatThrownBy(() -> Similarity.cosine(new float[]{1f, 2f}, new float[]{1f}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void embedAllPreservesOrder() {
        LocalHashEmbeddingService svc = new LocalHashEmbeddingService(64);
        List<float[]> vs = svc.embedAll(List.of("erster text", "zweiter text"));
        assertThat(vs).hasSize(2);
        assertThat(vs.get(0)).containsExactly(svc.embed("erster text"));
        assertThat(vs.get(1)).containsExactly(svc.embed("zweiter text"));
    }

    @Test
    void tinyDimensionsAreRefused() {
        assertThatThrownBy(() -> new LocalHashEmbeddingService(4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modelTagMakesTheTestBackendIdentityUnmissable() {
        assertThat(new LocalHashEmbeddingService(64).modelTag()).isEqualTo("local-hash-64");
    }
}
