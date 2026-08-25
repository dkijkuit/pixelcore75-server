package nl.ctasoftware.crypto.ticker.server.service.command;

import nl.ctasoftware.crypto.ticker.server.service.image.LatinFoldService;

import java.awt.Color;

/**
 * ACMD TEXT emission at the frame path's layout: sanitize first (the same Latin fold
 * the frame path's {@code PaintToolsService.drawText*} applies, strict-ASCII tail so
 * {@code requireAscii} can never fail a whole batch on one enriched data field),
 * measure with the panel's own integer advances, then emit TEXT at
 * {@code y = baselineY + lineTop} — the frame path's AWT baseline translated into the
 * ACMD line-box-top reference. Shared by the screen services whose frame path is a
 * plain {@code drawText}/{@code drawTextAlignRight}/{@code drawTextAlignCenter} render.
 */
public final class AcmdLayout {

    private AcmdLayout() {
    }

    /** Left-aligned TEXT at {@code x}, sanitized (empty strings emit nothing). */
    public static void left(final CommandBatch batch, final FontPageExtractor.FontPage page,
                            final int pageId, final String text, final int x,
                            final int baselineY, final Color color) {
        final String sanitized = sanitize(text);
        if (sanitized.isEmpty()) {
            return;
        }
        batch.text(pageId, x, baselineY + page.lineTop(), Rgb565.of(color), sanitized);
    }

    /** Right-aligned TEXT against the 64 px canvas edge (frame path's {@code 64 - width}). */
    public static void right(final CommandBatch batch, final FontPageExtractor.FontPage page,
                             final int pageId, final String text, final int baselineY, final Color color) {
        final String sanitized = sanitize(text);
        if (sanitized.isEmpty()) {
            return;
        }
        batch.text(pageId, AcmdMirror.WIDTH - page.width(sanitized), baselineY + page.lineTop(),
                Rgb565.of(color), sanitized);
    }

    /** Center-aligned TEXT (frame path's {@code 32 - width/2}). */
    public static void center(final CommandBatch batch, final FontPageExtractor.FontPage page,
                              final int pageId, final String text, final int baselineY, final Color color) {
        final String sanitized = sanitize(text);
        if (sanitized.isEmpty()) {
            return;
        }
        batch.text(pageId, AcmdMirror.WIDTH / 2 - page.width(sanitized) / 2, baselineY + page.lineTop(),
                Rgb565.of(color), sanitized);
    }

    /**
     * The frame path folds Latin diacritics/ligatures before rendering; the ACMD payload
     * must carry the folded ASCII (any surviving non-ASCII becomes {@code ?}, which the
     * panel renders as a glyph instead of failing the batch build).
     */
    public static String sanitize(final String s) {
        return LatinFoldService.foldToAsciiStrict(s);
    }
}
