package nl.ctasoftware.crypto.ticker.server.service.screen;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenConfig;

import java.util.List;
import java.util.function.Supplier;

/**
 * A ScreenService whose screens can additionally render as an ACMD v1 command batch
 * (plan §6 / the AGENTS.md protocol section): {@link #renderCommandBatch} returns a
 * fully framed batch ({@code "ACMD" + version + cmdCount + commands}) that the panel
 * executes locally — static primitives on a base canvas plus the parametric primitives
 * (SWEEP/SCROLL/BLINK) it ticks for the whole slot. ALL parametrics arm, in command
 * order, up to {@code AcmdOpcode.PARAMS_MAX} (4): each tick composites their overlays
 * over a fresh copy of the base in that order, so a later overlay draws over an earlier
 * one where their regions overlap. An identical-parametric-sequence republish carries
 * the previous epoch (phases continue across refreshes); any change re-arms all of them.
 *
 * <p>The command path is the screen's only rendering path: the job routes every config
 * with {@code commandCapable == true} here (bitmap screens — ANIMATION, IMAGE, and
 * non-parametric CUSTOM designs — keep the static/ANIM paths instead; see
 * {@code StaticScreenService}).</p>
 */
public interface CommandScreenService<T extends ScreenConfig> extends ScreenService<T> {

    /**
     * Fully framed ACMD v1 batch for this screen (black canvas start; FONT pages must
     * precede the TEXT/SCROLL commands referencing them; batches are self-contained).
     */
    byte[] renderCommandBatch(T screenConfig);

    /**
     * Whether this specific config renders via commands. Services whose every config
     * encodes as commands keep the default; a service with a per-config choice (CUSTOM:
     * only designs carrying parametric layers — pxd spec §3.6 — profit from commands;
     * static/animation designs keep the retained-frame/ANIM paths) overrides this so the
     * job gates the command branch without a failing batch build (and without skipping
     * ANIM staging for frame designs).
     */
    default boolean commandCapable(T screenConfig) {
        return true;
    }

    /**
     * Multi-page command screens (e.g. CLOSEST's cycling info pages): batches in page
     * order, republished by the job — the first at the slot start, each next one at
     * every {@code pageDwellMs} boundary (a fresh batch restarts its parametrics, which
     * is exactly what the panel does on arrival), the last page holding until the slot
     * ends. Single-page screens keep the default: one batch, no dwell.
     */
    default BatchStream renderCommandBatches(final T screenConfig) {
        return new BatchStream(List.of(renderCommandBatch(screenConfig)), 0);
    }

    /**
     * Live-refreshing command screens (e.g. RADAR's re-fetched blips): the fully
     * rendered first batch plus a supplier for every following one, re-rendered with
     * fresh data and republished by the job on the {@code refreshMs} grid until the
     * slot ends. When the parametric sequence is IDENTICAL between refreshes the panel
     * carries the previous epoch (phases run continuously — the radar's stable
     * enrichment scrolls rely on this); when it changes (page swap, new data) every
     * parametric re-arms at 0, so each parametric's loop should complete a whole
     * number of cycles per refresh for a seamless restart (e.g. SWEEP at 180°/s with a
     * 2000 ms refresh = exactly one revolution per interval). Null (the default) for
     * screens whose data has no live value — they keep the {@link BatchStream} path.
     */
    default RefreshStream renderCommandRefresh(final T screenConfig) {
        return null;
    }

    /** Command batches in page order plus the dwell each is shown for (0 = single page). */
    record BatchStream(List<byte[]> batches, long pageDwellMs) {
    }

    /**
     * Live-refreshing command stream: the first batch (rendered eagerly so a build
     * failure falls back before the slot starts) and the supplier for each following
     * refresh render.
     */
    record RefreshStream(byte[] firstBatch, Supplier<byte[]> nextBatches, long refreshMs) {
    }
}
