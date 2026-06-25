package com.akamai.miniwsa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Mini WSA (Web Security Analytics) service.
 *
 * <p>The service ingests security events over REST, enriches them with an attack
 * type and a threat score, persists them, and exposes analytics APIs. See the
 * package layout for the clean/hexagonal split: {@code api}, {@code application},
 * {@code domain}, {@code infrastructure}, {@code generator}.
 */
@SpringBootApplication
public class MiniWsaApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniWsaApplication.class, args);
    }
}
