package com.keplura.server.datasource;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SmbSourceConnectorTest {
    @Test void missingDescriptorNeverBecomesPublicAccess() {
        assertThat(SmbSourceConnector.permissions(null).allow()).isEmpty();
        assertThat(SmbSourceConnector.permissions(null).deny()).containsExactly("everyone");
    }

    @Test void standardAndPosixSidsUseCanonicalPrincipals() {
        assertThat(SmbSourceConnector.principal("S-1-1-0")).isEqualTo("everyone");
        assertThat(SmbSourceConnector.principal("S-1-5-11")).isEqualTo("everyone");
        assertThat(SmbSourceConnector.principal("S-1-22-1-2001")).isEqualTo("posix-uid:2001");
        assertThat(SmbSourceConnector.principal("S-1-22-2-3001")).isEqualTo("posix-gid:3001");
        assertThat(SmbSourceConnector.principal("S-1-5-21-42")).isEqualTo("sid:S-1-5-21-42");
    }
}
