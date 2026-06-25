package com.akamai.miniwsa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * Entry point for the Mini WSA (Web Security Analytics) service.
 *
 * <p>The service ingests security events over REST, enriches them with an attack
 * type and a threat score, persists them, and exposes analytics APIs. See the
 * package layout for the clean/hexagonal split: {@code api}, {@code application},
 * {@code domain}, {@code infrastructure}, {@code generator}.
 *
 * <p>{@code DataSourceAutoConfiguration} is excluded so the default in-memory
 * storage profile boots without any database configured; the ClickHouse adapter
 * builds its own {@code DataSource} only when {@code miniwsa.storage=clickhouse}.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class MiniWsaApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniWsaApplication.class, args);
    }
}
