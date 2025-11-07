package nl.ctasoftware.crypto.ticker.server.config;

import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.awt.*;
import java.io.File;
import java.io.IOException;

@Configuration
public class PaintConfig {
    @Bean
    GraphicsEnvironment graphicsEnvironment() {
        return GraphicsEnvironment.getLocalGraphicsEnvironment();
    }

    @Bean
    Font miniLineFont8Px(final GraphicsEnvironment ge) throws IOException, FontFormatException {
        Font miniLineFont = Font.createFont(Font.TRUETYPE_FONT, new File("assets/fonts/MiniLine2.ttf"));
        ge.registerFont(miniLineFont);
        return miniLineFont.deriveFont(8f);
    }

    @Bean
    Font habboFont8Px(final GraphicsEnvironment ge) throws IOException, FontFormatException {
        Font habboFont = Font.createFont(Font.TRUETYPE_FONT, new File("assets/fonts/Habbo.ttf"));
        ge.registerFont(habboFont);
        return habboFont.deriveFont(16f);
    }

    @Bean
    Font ledBoardFont8Px(final GraphicsEnvironment ge) throws IOException, FontFormatException {
        Font ledBoardFont = Font.createFont(Font.TRUETYPE_FONT, new File("assets/fonts/EXEPixelPerfect.ttf"));
        ge.registerFont(ledBoardFont);
        return ledBoardFont.deriveFont(16f);
    }

    @Bean
    Font tinyUnicode8Px(final GraphicsEnvironment ge) throws IOException, FontFormatException {
        Font tinyFont = Font.createFont(Font.TRUETYPE_FONT, new File("assets/fonts/TinyUnicode.ttf"));
        ge.registerFont(tinyFont);
        return tinyFont.deriveFont(16f);
    }

    @Bean
    Font cgPixel5Px(final GraphicsEnvironment ge) throws IOException, FontFormatException {
        Font cgPixel = Font.createFont(Font.TRUETYPE_FONT, new File("assets/fonts/cg-pixel-4x5.ttf"));
        ge.registerFont(cgPixel);
        return cgPixel.deriveFont(5f);
    }

    @Bean
    Font frostFont4Px(final GraphicsEnvironment ge) throws IOException, FontFormatException {
        Font frostFont = Font.createFont(Font.TRUETYPE_FONT, new File("assets/fonts/frostfont-logo.ttf"));
        ge.registerFont(frostFont);
        return frostFont.deriveFont(7f);
    }

    @Bean
    Font grinched7Px(final GraphicsEnvironment ge) throws IOException, FontFormatException {
        Font grinchedFont = Font.createFont(Font.TRUETYPE_FONT, new File("assets/fonts/grinched-4x7.ttf"));
        ge.registerFont(grinchedFont);
        return grinchedFont.deriveFont(9f);
    }
}
