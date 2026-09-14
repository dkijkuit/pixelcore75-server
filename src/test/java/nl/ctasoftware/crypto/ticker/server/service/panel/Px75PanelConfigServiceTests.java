package nl.ctasoftware.crypto.ticker.server.service.panel;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.ImageScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.repository.PanelConfigRepository;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the review 2026-08-27 fix: IMAGE screens are validated at save (400-class
 * failure) instead of saving garbage that then fails at render on every cycle.
 */
@ExtendWith(MockitoExtension.class)
class Px75PanelConfigServiceTests {

    @Mock PanelConfigRepository panelConfigRepository;

    @InjectMocks Px75PanelConfigService service;

    @BeforeEach
    void resetService() {
        service = new Px75PanelConfigService(panelConfigRepository, new ImageService());
    }

    private static String pngDataUrl(int w, int h) throws Exception {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", bos);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    @Test
    void saveRejectsImageWithInvalidPayload() {
        final Px75PanelConfig config = new Px75PanelConfig(1L, List.of(
                new ImageScreenConfig(ScreenType.IMAGE, 5, "photo.png", "data:image/png;base64,not-base64!!!", false)));

        assertThrows(IllegalArgumentException.class, () -> service.save(config));
        verify(panelConfigRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void saveRejectsImageWithWrongDimensions() throws Exception {
        final Px75PanelConfig config = new Px75PanelConfig(1L, List.of(
                new ImageScreenConfig(ScreenType.IMAGE, 5, "photo.png", pngDataUrl(63, 32), false)));

        assertThrows(IllegalArgumentException.class, () -> service.save(config));
    }

    @Test
    void saveRejectsImageWithoutUploadDataInsteadOfNpe() {
        final Px75PanelConfig config = new Px75PanelConfig(1L, List.of(
                new ImageScreenConfig(ScreenType.IMAGE, 5, "photo.png", null, false)));

        assertThrows(IllegalArgumentException.class, () -> service.save(config));
    }

    @Test
    void saveAcceptsValidImage() throws Exception {
        final Px75PanelConfig config = new Px75PanelConfig(1L, List.of(
                new ImageScreenConfig(ScreenType.IMAGE, 5, "photo.png", pngDataUrl(64, 32), false)));
        when(panelConfigRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertEquals(config, service.save(config));
    }

    @Test
    void saveDoesNotTouchNonImageScreens() {
        final Px75PanelConfig config = new Px75PanelConfig(1L, List.of(
                new nl.ctasoftware.crypto.ticker.server.model.panel.config.ClockScreenConfig(
                        ScreenType.CLOCK, 5, "Europe/Amsterdam", true, "FFFFFF", false)));
        when(panelConfigRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertEquals(config, service.save(config));
    }
}
