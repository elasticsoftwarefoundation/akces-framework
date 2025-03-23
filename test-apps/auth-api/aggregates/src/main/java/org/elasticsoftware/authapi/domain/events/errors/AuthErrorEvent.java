package org.elasticsoftware.authapi.domain.events.errors;

import org.elasticsoftware.akces.events.ErrorEvent;

/**
 * Extended ErrorEvent interface for authentication errors.
 * This interface adds the getMessage method required by all our error events
 * without modifying the framework's original ErrorEvent interface.
 */
public interface AuthErrorEvent extends ErrorEvent {
    /**
     * Gets the error message associated with this error event.
     *
     * @return The error message
     */
    String getMessage();
}