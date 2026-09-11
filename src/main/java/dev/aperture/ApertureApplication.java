package dev.aperture;

import dev.aperture.config.ApertureProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Aperture - a live equities market-data and trading console built on the Webull OpenAPI, with an
 * LLM analyst over the live book.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ApertureProperties.class)
public class ApertureApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApertureApplication.class, args);
    }
}
