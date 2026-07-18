package com.williamcallahan.javachat.support.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Isolates expected test logs without changing the logger configuration visible after the test.
 */
public final class ExpectedLogEvents implements AutoCloseable {
    private final Logger logger;
    private final Level originalLoggerLevel;
    private final boolean originalLoggerAdditivity;
    private final List<Appender<ILoggingEvent>> originalDirectAppenders;
    private final ListAppender<ILoggingEvent> expectedLogAppender = new ListAppender<>();
    private boolean closed;

    private ExpectedLogEvents(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        originalLoggerLevel = logger.getLevel();
        originalLoggerAdditivity = logger.isAdditive();
        originalDirectAppenders = directAppenders(logger);

        originalDirectAppenders.forEach(directAppender -> logger.detachAppender(directAppender));
        logger.setAdditive(false);
        expectedLogAppender.start();
        logger.addAppender(expectedLogAppender);
    }

    /**
     * Captures direct events from a logger while withholding them from inherited and direct appenders.
     */
    public static ExpectedLogEvents capture(Logger logger) {
        return new ExpectedLogEvents(logger);
    }

    /**
     * Returns an immutable snapshot of the events emitted while this capture is active.
     */
    public List<ILoggingEvent> events() {
        return List.copyOf(expectedLogAppender.list);
    }

    /**
     * Restores the logger's original level, additivity, and direct appenders.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }

        logger.detachAppender(expectedLogAppender);
        expectedLogAppender.stop();
        directAppenders(logger).forEach(directAppender -> logger.detachAppender(directAppender));
        logger.setLevel(originalLoggerLevel);
        logger.setAdditive(originalLoggerAdditivity);
        originalDirectAppenders.forEach(directAppender -> logger.addAppender(directAppender));
        closed = true;
    }

    private static List<Appender<ILoggingEvent>> directAppenders(Logger logger) {
        List<Appender<ILoggingEvent>> directAppenders = new ArrayList<>();
        logger.iteratorForAppenders().forEachRemaining(directAppenders::add);
        return List.copyOf(directAppenders);
    }
}
