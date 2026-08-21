package nl.ctasoftware.crypto.ticker.server.service.screen;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenConfig;

import java.util.List;

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

    /** Command batches in page order plus the dwell each is shown for (0 = single page). */
    record BatchStream(List<byte[]> batches, long pageDwellMs) {
    }
}
