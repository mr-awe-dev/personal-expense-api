package com.fastwork.expense.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.StringWriter;
import java.time.Duration;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.logstash.logback.marker.LogstashMarker;

class LoggingAspectTest {

    private final ObservabilityProperties properties = new ObservabilityProperties();
    private final LoggingAspect aspect = new LoggingAspect(properties);

    private Logger aspectLogger;
    private ListAppender<ILoggingEvent> appender;
    private Level originalLevel;

    @BeforeEach
    void attachAppender() {
        aspectLogger = (Logger) LoggerFactory.getLogger(LoggingAspect.class);
        originalLevel = aspectLogger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        aspectLogger.addAppender(appender);
        aspectLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachAppender() {
        aspectLogger.detachAppender(appender);
        aspectLogger.setLevel(originalLevel);
    }

    private ProceedingJoinPoint joinPoint(Runnable body, Throwable toThrow) throws Throwable {
        Signature signature = mock(Signature.class);
        when(signature.getDeclaringType()).thenReturn(TransactionServiceProbe.class);
        when(signature.getName()).thenReturn("list");
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.proceed())
                .thenAnswer(
                        invocation -> {
                            if (body != null) {
                                body.run();
                            }
                            if (toThrow != null) {
                                throw toThrow;
                            }
                            return "result";
                        });
        return joinPoint;
    }

    /**
     * Nama kelas berakhiran Service agar representatif; hanya dipakai sebagai declaringType mock.
     */
    static final class TransactionServiceProbe {}

    @Test
    void fastOperationLogsAtDebugWithStructuredMarker() throws Throwable {
        Object result = aspect.measure(joinPoint(null, null));

        assertThat(result).isEqualTo("result");
        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(event.getFormattedMessage()).contains("TransactionServiceProbe.list");
        assertThat(markerJson(event)).contains("\"operation\"").contains("\"durationMs\"");
    }

    @Test
    void slowOperationLogsAtWarn() throws Throwable {
        properties.setSlowThreshold(Duration.ofMillis(50));
        Runnable sleep =
                () -> {
                    try {
                        Thread.sleep(120);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                };

        aspect.measure(joinPoint(sleep, null));

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(markerJson(event)).contains("\"slow\":true");
    }

    @Test
    void exceptionPropagatesUnchangedAndIsNotLoggedAsError() throws Throwable {
        IllegalStateException boom = new IllegalStateException("boom");

        assertThatThrownBy(() -> aspect.measure(joinPoint(null, boom))).isSameAs(boom);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.DEBUG);
        assertThat(markerJson(appender.list.get(0))).contains("\"outcome\":\"error\"");
    }

    private String markerJson(ILoggingEvent event) {
        try {
            StringWriter writer = new StringWriter();
            JsonGenerator generator = new JsonFactory().createGenerator(writer);
            generator.writeStartObject();
            ((LogstashMarker) event.getMarker()).writeTo(generator);
            generator.writeEndObject();
            generator.flush();
            return writer.toString();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
