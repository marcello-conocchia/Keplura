package com.keplura.server.search;

import org.junit.jupiter.api.Test;

import static com.keplura.server.search.PgVectorSchema.Decision.ADAPT;
import static com.keplura.server.search.PgVectorSchema.Decision.KEEP_MISMATCH;
import static com.keplura.server.search.PgVectorSchema.Decision.MATCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-0010: the decision rule for retyping the pgvector twin column. The
 * live ALTER path needs a real PostgreSQL+pgvector and is covered by the
 * evidence run (docs/development.md); the rule itself is hermetic.
 */
class PgVectorSchemaTest {

    @Test
    void matchingDimensionNeverChangesAnything() {
        assertThat(PgVectorSchema.decide(1024, 1024, 500, 500, true)).isEqualTo(MATCH);
        assertThat(PgVectorSchema.decide(64, 64, 0, 0, false)).isEqualTo(MATCH);
    }

    @Test
    void emptyIndexFollowsTheConfiguredModel() {
        // fresh install: V5 created vector(64), compose configures bge-m3
        assertThat(PgVectorSchema.decide(64, 1024, 0, 0, true)).isEqualTo(ADAPT);
    }

    @Test
    void populatedIndexIsNeverRetyped() {
        assertThat(PgVectorSchema.decide(64, 1024, 1, 0, true)).isEqualTo(KEEP_MISMATCH);
        assertThat(PgVectorSchema.decide(64, 1024, 0, 1, true)).isEqualTo(KEEP_MISMATCH);
        assertThat(PgVectorSchema.decide(1024, 768, 10, 10, true)).isEqualTo(KEEP_MISMATCH);
    }

    @Test
    void autoAdaptOffKeepsTheMismatch() {
        assertThat(PgVectorSchema.decide(64, 1024, 0, 0, false)).isEqualTo(KEEP_MISMATCH);
    }

    @Test
    void parsesPgFormatType() {
        assertThat(PgVectorSchema.parseDimension("vector(64)")).isEqualTo(64);
        assertThat(PgVectorSchema.parseDimension("vector(1024)")).isEqualTo(1024);
        assertThat(PgVectorSchema.parseDimension("vector")).isEqualTo(-1);
        assertThat(PgVectorSchema.parseDimension("text")).isEqualTo(-1);
        assertThat(PgVectorSchema.parseDimension(null)).isEqualTo(-1);
        assertThat(PgVectorSchema.parseDimension("vector(x)")).isEqualTo(-1);
    }

    @Test
    void dimensionBoundsMatchThePortableColumn() {
        assertThatCode(() -> PgVectorSchema.requireValidDimension(1)).doesNotThrowAnyException();
        assertThatCode(() -> PgVectorSchema.requireValidDimension(PgVectorSchema.MAX_DIMENSION))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> PgVectorSchema.requireValidDimension(0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PgVectorSchema.requireValidDimension(PgVectorSchema.MAX_DIMENSION + 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void worstCaseEncodingAtMaxDimensionFitsVarchar65536() {
        float[] v = new float[PgVectorSchema.MAX_DIMENSION];
        java.util.Arrays.fill(v, -1.23456795E-10f); // longest Float.toString shape
        assertThat(EmbeddingCodec.encode(v).length()).isLessThanOrEqualTo(65536);
    }
}
