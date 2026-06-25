package com.akamai.miniwsa;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Smoke test for the Phase 1 skeleton: verifies the Spring context loads and the
 * liveness endpoint responds. Acts as the baseline integration test the later
 * API integration tests will build on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MiniWsaApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void contextLoadsAndPingResponds() {
        String body = restTemplate.getForObject("http://localhost:" + port + "/ping", String.class);
        assertThat(body).contains("ok").contains("mini-wsa");
    }
}
