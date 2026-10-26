package com.shoplab.helpdesk.architecture.violations.http;

import org.springframework.web.client.RestTemplate;

/** Fixture: a blocking HTTP client (violates A4). */
public class LegacyHttpCall {

    public String fetch(String url) {
        return new RestTemplate().getForObject(url, String.class);
    }
}
