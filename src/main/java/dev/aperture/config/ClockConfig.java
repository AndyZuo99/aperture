package dev.aperture.config;

import dev.aperture.time.MarketClock;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the clock.
 *
 * <p>A bean rather than {@code Instant.now()} scattered through the code, so a test can inject a
 * fixed clock and assert behaviour at 09:29 versus 09:31 without waiting for the market to open.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    public MarketClock marketClock(Clock clock) {
        return new MarketClock(clock);
    }
}
