package org.watermedia.api.platform.extension.botguard;

/**
 * Raised when the rustypipe-botguard native tool cannot be resolved, executed or parsed. Carrying a
 * dedicated checked type keeps BotGuard failures distinguishable from generic IO/extraction errors,
 * so callers can degrade gracefully (skip the po_token path) instead of crashing playback.
 */
public class BotGuardException extends Exception {
    public BotGuardException(final String message) {
        super(message);
    }

    public BotGuardException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
