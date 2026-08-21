package nl.ctasoftware.crypto.ticker.server.service.screen;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenConfig;

import java.util.List;
import java.util.function.Supplier;

/**
 * A ScreenService whose screens can additionally render as an ACMD v1 command batch
 * (plan §6 / the AGENTS.md protocol section): {@link #renderCommandBatch} returns a
 * fully framed batch ({@code "ACMD" + version + cmdCount + commands}) that the panel
 * executes locally — static primitives on a base canvas plus at most one parametric
 * primitive (SWEEP/SCROLL/BLINK, first wins) it ticks for the whole slot.
 *
 * <p>Both render paths stay implemented: the job picks the command path only while
 * {@code pixelcore75.command-encoding.enabled} is true, and falls back to this service's
 * frame/static path otherwise (or when the batch build fails). The command path must
 * reproduce the frame path's look via commands — golden-image parity tests pin it.
 */
public interface CommandScreenService<T extends ScreenConfig> extends ScreenService<T> {

    /**
     * Fully framed ACMD v1 batch for this screen (black canvas start; FONT pages must
     * precede the TEXT/SCROLL commands referencing them; batches are self-contained).
     */
    byte[] renderCommandBatch(T screenConfig);

    /**
     * Multi-page command screens (e.g. CLOSEST's cycling info pages): batches in page
     * order, republished by the job — the first at the slot start, each next one at
     * every {@code pageDwellMs} boundary (a fresh batch restarts its parametric, which
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
     * slot ends. The cadence must make the batch's parametric loop whole (e.g. SWEEP
     * at 180°/s with a 2000 ms refresh = exactly one revolution per interval): the
     * panel re-arms the parametric at each commit, so a whole-loop cadence restarts
     * it exactly where the previous one wrapped and the republish is invisible —
     * and on carry-capable firmware an identical-SWEEP commit keeps the previous
     * epoch outright (phase continuous regardless of arrival jitter). A
     * {@code refreshMs} that is not a whole multiple of the parametric loop visibly
     * snaps the animation back at every refresh. Null (the default) for screens
     * whose data has no live value — they keep the {@link BatchStream} path.
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
