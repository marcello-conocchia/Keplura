package com.keplura.server.websearch;

import com.keplura.server.admin.SsrfGuard;

/** Test access to the package-private guard setter (used by tool tests against a local provider stub). */
public final class WebSearchTestSupport {
    private WebSearchTestSupport() { }

    public static WebSearchService withGuard(WebSearchService service, SsrfGuard guard) {
        service.setGuard(guard);
        return service;
    }
}
