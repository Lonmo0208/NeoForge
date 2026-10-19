/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.util.log.LogHandler;
import net.fabricmc.loader.impl.util.log.LogLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * Routes fabric-loader's own logging into the platform's log4j.
 *
 * <p>The loader starts with {@code BuiltinLogHandler}, which buffers every message instead of printing it
 * until something calls {@code Log.init} or an ERROR arrives - INFO output is held in memory and only
 * dumped to {@code fabricloader.log} by a shutdown hook. On a plain Fabric install {@code Knot} calls
 * {@code Log.init} within its first second, so the buffer never becomes visible; here the bridge hosts the
 * loader and nothing did, so the whole "Loading N mod(s)" block - the single most useful line a Fabric
 * user looks for - stayed invisible until the server shut down, at which point it appeared in a separate
 * file rather than in console and {@code latest.log}.
 *
 * <p>Installing this handler (see {@link #install()}) makes the loader log through the same log4j the rest
 * of the server uses. {@code Log.init} replays whatever the builtin handler buffered into the new handler,
 * so the messages logged before this point are not lost - they are emitted, in order, at install time.
 *
 * <p>The message keeps fabric-loader's own {@code [Category/]} prefix but drops its timestamp: log4j stamps
 * every line itself, and two timestamps on one line read as a bug. {@code shouldLog} always answers true -
 * the loader's levels do not map one-to-one onto log4j's (DEBUG and TRACE are both finer than INFO here) and
 * a level that the platform filters out costs nothing.
 */
final class BridgedLogHandler implements LogHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("FabricLoader");
    private static volatile boolean installed;

    private BridgedLogHandler() {}

    /**
     * Points fabric-loader's {@code Log} at this handler, once. Safe to call from wherever the bridge boots;
     * a second call would replay the (now empty) buffer again for no benefit.
     */
    static void install() {
        if (installed) {
            return;
        }
        synchronized (BridgedLogHandler.class) {
            if (installed) {
                return;
            }
            try {
                Log.init(new BridgedLogHandler());
                installed = true;
            } catch (Throwable t) {
                // Never let a logging setup failure take the boot down; the loader still works, its
                // messages just stay buffered as before.
                LOGGER.warn("[NeoFobric] Could not route fabric-loader's log into log4j", t);
            }
        }
    }

    @Override
    public void log(long time, LogLevel level, LogCategory category, String msg, Throwable exc,
            boolean fromReplay, boolean wasSuppressed) {
        var line = describe(category, msg);
        var slf4jLevel = levelOf(level);
        if (exc != null) {
            LOGGER.atLevel(slf4jLevel).setCause(exc).log(line);
        } else {
            LOGGER.atLevel(slf4jLevel).log(line);
        }
    }

    @Override
    public boolean shouldLog(LogLevel level, LogCategory category) {
        return true;
    }

    @Override
    public void close() {
        // Nothing is held open: log4j owns the writers.
    }

    /** {@return the message with fabric-loader's {@code [Category]} tag, or bare when it has no category} */
    private static String describe(LogCategory category, String msg) {
        // LogCategory#toString already renders "FabricLoader/Discovery" (and the bare context when it has no
        // name), which is the shape a Fabric user recognises - no need to rebuild it from the fields.
        return category == null ? String.valueOf(msg) : "[" + category + "] " + msg;
    }

    private static Level levelOf(LogLevel level) {
        return switch (level) {
            case ERROR -> Level.ERROR;
            case WARN -> Level.WARN;
            case INFO -> Level.INFO;
            case DEBUG -> Level.DEBUG;
            case TRACE -> Level.TRACE;
        };
    }
}
