package com.fastwork.expense.observability;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Konfigurasi {@code app.observability.*} — pemantauan performa service layer. */
@ConfigurationProperties(prefix = "app.observability")
public class ObservabilityProperties {

    private boolean enabled = true;
    private Duration slowThreshold = Duration.ofMillis(500);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getSlowThreshold() {
        return slowThreshold;
    }

    public void setSlowThreshold(Duration slowThreshold) {
        this.slowThreshold = slowThreshold;
    }
}
