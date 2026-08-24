package nl.ctasoftware.crypto.ticker.server.service.screen.custom;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.CustomScreenConfig;

/**
 * Resolves a CUSTOM screen library reference into a hydrated config carrying the current
 * design (the reference id is preserved). Returns null when the referenced library entry
 * no longer exists, so the rotation loop can skip it instead of failing the whole panel.
 */
@FunctionalInterface
public interface CustomScreenResolver {

    CustomScreenConfig hydrate(CustomScreenConfig config);
}
