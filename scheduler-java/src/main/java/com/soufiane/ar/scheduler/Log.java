package com.soufiane.ar.scheduler;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Minimal timestamped logging to stderr, same style as the C++ worker. */
final class Log {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {}

    static void info(String message) {
        System.err.println(LocalTime.now().format(TIME) + " [scheduler] " + message);
    }
}
