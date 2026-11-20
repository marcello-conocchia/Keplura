package com.keplura.server.directory;

import org.junit.jupiter.api.Test;
import javax.naming.NamingEnumeration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LdapDirectoryClientTest {
    @Test void filterValuesCannotInjectLdapSyntax() {
        assertThat(LdapDirectoryClient.escapeFilter("*)(uid=*)\\\0ä"))
                .isEqualTo("\\2a\\29\\28uid=\\2a\\29\\5c\\00ä");
    }

    @Test void sidRoundtripAndTruncatedSidNeverBecomeAnotherPrincipal() {
        String sid = "S-1-5-21-4294967295-32-3001";
        byte[] bytes = LdapDirectoryClient.sidToBytes(sid);
        assertThat(LdapDirectoryClient.sidToString(bytes)).isEqualTo(sid);
        assertThat(LdapDirectoryClient.sidToString(java.util.Arrays.copyOf(bytes, bytes.length - 4)))
                .isEqualTo("invalid");
    }

    @Test void referralsAreIgnoredButConnectionFailureIsNot() throws Exception {
        NamingEnumeration<?> entries = mock(NamingEnumeration.class);
        when(entries.hasMore()).thenReturn(true).thenThrow(new javax.naming.PartialResultException());
        assertThat(LdapDirectoryClient.hasMoreLocal(entries)).isTrue();
        assertThat(LdapDirectoryClient.hasMoreLocal(entries)).isFalse();
        doThrow(new javax.naming.CommunicationException()).when(entries).hasMore();
        assertThatThrownBy(() -> LdapDirectoryClient.hasMoreLocal(entries))
                .isInstanceOf(javax.naming.CommunicationException.class);
    }

    @Test void emptyPasswordCannotCauseAnonymousBind() throws Exception {
        assertThat(new LdapDirectoryClient().authenticate(null, null, "user", "")).isNull();
    }
}
