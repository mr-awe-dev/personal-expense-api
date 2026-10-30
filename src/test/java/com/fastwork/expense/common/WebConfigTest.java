package com.fastwork.expense.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

class WebConfigTest {

    private final WebConfig config = new WebConfig();

    @Test
    void nullOriginsRegisterNothing() {
        config.setAllowedOrigins(null);
        CapturingRegistry registry = new CapturingRegistry();
        config.addCorsMappings(registry);
        assertThat(registry.mapped).isZero();
    }

    @Test
    void blankOriginsRegisterNothing() {
        config.setAllowedOrigins("   ");
        CapturingRegistry registry = new CapturingRegistry();
        config.addCorsMappings(registry);
        assertThat(registry.mapped).isZero();
    }

    @Test
    void configuredOriginsRegisterApiMapping() {
        config.setAllowedOrigins("https://a.example.com,https://b.example.com");
        CapturingRegistry registry = new CapturingRegistry();
        config.addCorsMappings(registry);
        assertThat(registry.mapped).isEqualTo(1);
    }

    @Test
    void emptyDefaultOriginsRegisterNothing() {
        CapturingRegistry registry = new CapturingRegistry();
        new WebConfig().addCorsMappings(registry);
        assertThat(registry.mapped).isZero();
    }

    /** CorsRegistry ringan untuk menghitung pendaftaran mapping. */
    static final class CapturingRegistry extends CorsRegistry {
        int mapped;

        @Override
        public org.springframework.web.servlet.config.annotation.CorsRegistration addMapping(
                String pathPattern) {
            mapped++;
            return super.addMapping(pathPattern);
        }
    }
}
