package nl.ctasoftware.crypto.ticker.server.controller;

import lombok.RequiredArgsConstructor;
import nl.ctasoftware.crypto.ticker.server.model.Px75User;
import nl.ctasoftware.crypto.ticker.server.model.dto.CustomScreenDto;
import nl.ctasoftware.crypto.ticker.server.model.dto.CustomScreenPreviewRequest;
import nl.ctasoftware.crypto.ticker.server.model.dto.CustomScreenPreviewResponse;
import nl.ctasoftware.crypto.ticker.server.model.dto.FontPageResponse;
import nl.ctasoftware.crypto.ticker.server.model.dto.SaveCustomScreenRequest;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.screen.FrameScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.custom.CustomScreenLibraryService;
import nl.ctasoftware.crypto.ticker.server.service.screen.custom.CustomScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.custom.PxdFont;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.List;

@RestController
@RequestMapping("/v1/screen/custom")
@RequiredArgsConstructor
public class CustomScreenController {

    final CustomScreenService customScreenService;
    final CustomScreenLibraryService customScreenLibraryService;

    /* ---------------------------------------------------------------
     * Library CRUD (user-owned custom screens)
     * ------------------------------------------------------------- */

    /** Everything the user may see: their own screens plus others' shared ones (admin: all). */
    @GetMapping
    List<CustomScreenDto> list(@AuthenticationPrincipal final Px75User user) {
        return customScreenLibraryService.listVisible(user);
    }

    /** Full detail including the design and how many panel rotations reference it. */
    @GetMapping("{id}")
    CustomScreenDto get(@AuthenticationPrincipal final Px75User user, @PathVariable final long id) {
        return customScreenLibraryService.getVisible(user, id);
    }

    @PostMapping
    CustomScreenDto create(@AuthenticationPrincipal final Px75User user,
                           @RequestBody final SaveCustomScreenRequest request) {
        return customScreenLibraryService.create(user, request);
    }

    @PutMapping("{id}")
    CustomScreenDto update(@AuthenticationPrincipal final Px75User user,
                           @PathVariable final long id,
                           @RequestBody final SaveCustomScreenRequest request) {
        return customScreenLibraryService.update(user, id, request);
    }

    /**
     * Panel rotations referencing this screen keep their (now dangling) entries until the
     * panel config is saved again; the rotation loop skips them with a warning. The UI
     * shows the reference count from {@link #get} before deleting.
     */
    @DeleteMapping("{id}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal final Px75User user, @PathVariable final long id) {
        customScreenLibraryService.delete(user, id);
        return ResponseEntity.noContent().build();
    }

    /* ---------------------------------------------------------------
     * Design preview (server-truth rendering, spec §5.9)
     * ------------------------------------------------------------- */

    @PostMapping("preview")
    CustomScreenPreviewResponse preview(@RequestBody final CustomScreenPreviewRequest request) {
        final FrameScreenService.FrameStream stream = customScreenService.renderPreview(request.design());
        final List<String> frames = stream.frames().stream()
                .map(CustomScreenController::toPngDataUrl)
                .toList();
        return new CustomScreenPreviewResponse(frames, (int) stream.frameDelayMs());
    }

    /**
     * ACMD FONT-page glyphs for a pxd font (spec §3.3): the exact bitmaps the panel
     * draws for TEXT/SCROLL, so the designer's edit canvas composites text pixel-exact
     * instead of approximating with browser fonts. Pure function of the font's TTF.
     */
    @GetMapping("fontpage/{font}")
    FontPageResponse fontPage(@PathVariable final String font) {
        final PxdFont pxdFont = PxdFont.fromId(font);
        if (pxdFont == null) {
            throw new IllegalArgumentException("unknown font " + font);
        }
        final FontPageExtractor.FontPage page = customScreenService.fontPage(pxdFont);
        final List<FontPageResponse.GlyphResponse> glyphs = page.glyphs().stream()
                .map(g -> new FontPageResponse.GlyphResponse(g.code(), g.w(), g.h(),
                        g.xAdvance(), g.xOff(), g.yOff(),
                        Base64.getEncoder().encodeToString(g.bitmap())))
                .toList();
        return new FontPageResponse(pxdFont.name(), customScreenService.ascent(pxdFont),
                page.lineTop(), glyphs);
    }

    private static String toPngDataUrl(final BufferedImage image) {
        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", bos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
