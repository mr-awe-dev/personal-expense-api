package com.fastwork.expense.observability;

import com.fasterxml.jackson.core.JsonGenerator;

import ch.qos.logback.classic.spi.ILoggingEvent;
import net.logstash.logback.composite.AbstractJsonProvider;

/**
 * Menulis field {@code monolith_module} hasil klasifikasi nama logger (lihat {@link
 * MonolithModules}).
 */
public class MonolithModuleJsonProvider extends AbstractJsonProvider<ILoggingEvent> {

    @Override
    public void writeTo(JsonGenerator generator, ILoggingEvent event) throws java.io.IOException {
        generator.writeStringField(
                "monolith_module", MonolithModules.fromLogger(event.getLoggerName()));
    }
}
