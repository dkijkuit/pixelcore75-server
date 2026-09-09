package nl.ctasoftware.crypto.ticker.server.service.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.CustomScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.FrameScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenConfig;
import nl.ctasoftware.crypto.ticker.server.service.screen.custom.CustomScreenResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Rotation planning extracted from the old {@code PanelScreenJob}: CUSTOM library hydration,
 * disabled-screen filtering, and the animation slot math. Pure logic — no I/O.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RotationPlanner {

    public enum Reason { NO_CONFIG, ALL_REFERENCES_DANGLING, ALL_DISABLED, OK }

    public record Rotation(Reason reason, List<? extends ScreenConfig> screens) {

        public boolean reschedules() {
            return switch (reason) {
                // The dangling case re-checks the library instead of stopping forever; an empty
                // or fully-disabled rotation stops until the next config save.
                case ALL_REFERENCES_DANGLING, OK -> true;
                case NO_CONFIG, ALL_DISABLED -> false;
            };
        }

        public long retryDelayMillis() {
            return reason == Reason.ALL_REFERENCES_DANGLING ? 30_000 : 0;
        }
    }

    /** Panel animation slots must match the firmware (ANIM_MAX_SLOTS = 32). */
    static final int MAX_ANIM_SLOTS = 32;

    private final CustomScreenResolver customScreenResolver;

    public Rotation plan(final Px75PanelConfig panelConfig) {
        final List<? extends ScreenConfig> rawConfig = panelConfig.getScreensConfig();
        if (rawConfig == null || rawConfig.isEmpty()) {
            return new Rotation(Reason.NO_CONFIG, List.of());
        }

        // Resolve CUSTOM library references to their current design each cycle (edits to a
        // library screen propagate to every panel using it). Dangling references are dropped
        // with a warning so one deleted screen cannot break the whole rotation.
        final List<? extends ScreenConfig> screensConfig = hydratedScreens(panelConfig.getPanelId(), rawConfig);
        if (screensConfig.isEmpty()) {
            return new Rotation(Reason.ALL_REFERENCES_DANGLING, List.of());
        }

        final List<? extends ScreenConfig> activeScreens =
                screensConfig.stream().filter(s -> !s.disabled()).toList();
        if (activeScreens.isEmpty()) {
            return new Rotation(Reason.ALL_DISABLED, List.of());
        }
        return new Rotation(Reason.OK, activeScreens);
    }

    /** Slot index for a frame-streaming screen: its ordinal among the rotation's frame screens, capped. */
    public static int animationSlotFor(final int screenIdx, final List<? extends ScreenConfig> configs) {
        int ordinal = 0;
        for (int i = 0; i <= screenIdx; i++) {
            if (producesFrames(configs.get(i))) {
                ordinal++;
            }
        }
        return (ordinal - 1) % MAX_ANIM_SLOTS;
    }

    public static boolean producesFrames(final ScreenConfig config) {
        return config instanceof FrameScreenConfig frameConfig && frameConfig.producesFrames();
    }

    private List<? extends ScreenConfig> hydratedScreens(final long panelId, final List<? extends ScreenConfig> rawConfig) {
        boolean anyReference = false;
        for (final ScreenConfig config : rawConfig) {
            if (config instanceof CustomScreenConfig custom && custom.customScreenId() != null) {
                anyReference = true;
                break;
            }
        }
        if (!anyReference) {
            return rawConfig;
        }
        final List<ScreenConfig> hydrated = new ArrayList<>(rawConfig.size());
        for (final ScreenConfig config : rawConfig) {
            if (config instanceof CustomScreenConfig custom && custom.customScreenId() != null) {
                final CustomScreenConfig resolved = customScreenResolver.hydrate(custom);
                if (resolved == null) {
                    log.warn("------> Custom screen {} referenced by panel {} no longer exists; skipping it",
                            custom.customScreenId(), panelId);
                    continue;
                }
                hydrated.add(resolved);
                continue;
            }
            hydrated.add(config);
        }
        return List.copyOf(hydrated);
    }
}
