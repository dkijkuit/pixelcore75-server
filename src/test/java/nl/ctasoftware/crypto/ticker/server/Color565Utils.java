package nl.ctasoftware.crypto.ticker.server;

import nl.ctasoftware.crypto.ticker.server.font.Font5x7;
import nl.ctasoftware.crypto.ticker.server.font.FontRenderer;
import nl.ctasoftware.crypto.ticker.server.font.Org01Font;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class Color565Utils {
    @Test
    void convertColor() throws IOException {
        File file = new File("assets/images/cat.png");
        BufferedImage image = ImageIO.read(file);

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if(count % 16 == 0){
                    sb.append("\n");
                }
                Color rgbColor = new Color(image.getRGB(x, y));
                int i = ((rgbColor.getRed() & 0b11111000) << 8) | ((rgbColor.getGreen() & 0b11111100) << 3) | ((rgbColor.getBlue() & 0b11111000) >> 3);
                sb.append(String.format("0x%04x, ", i));

                count++;
            }
        }
        System.out.println(sb.toString());
    }

    @Test
    void drawText() throws IOException, FontFormatException {
        int width = 64, height = 32;

        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        Font font = Font.createFont(Font.TRUETYPE_FONT, new File("5by7.ttf"));
        ge.registerFont(font);

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = (Graphics2D)img.getGraphics();
        g.setColor(Color.BLACK);
        g.setFont(font.deriveFont(7f));
        //g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
        g.fillRect(0, 0, width, height);

//        FontRenderer renderer = new FontRenderer(Font5x7.BITMAPS, Font5x7.GLYPHS, Font5x7.FIRST_CHAR);
//        renderer.drawText(g, "HELLO WORLD!", 0, 10, Color.GREEN);
        g.setColor(Color.GREEN);
        g.drawString("BTC   +2.48%", 0, 12);
        g.drawString("~102465", 0, 21);

        ImageIO.write(img, "png", new File("output_font.png"));
        System.out.println("Saved to output_font.png");
    }

    @Test
    void drawOnImage() throws IOException, FontFormatException {
        BufferedImage image = ImageIO.read(new File("landscape.png"));
        addText(image, "TESTING");
    }

    @Test
    void drawEmptyImage() throws IOException, FontFormatException {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
        addText(image, "TESTING");
    }

    void addText(BufferedImage img, String text) throws IOException, FontFormatException {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        Font habboFont = Font.createFont(Font.TRUETYPE_FONT, new File("exepixelperfect.medium.ttf"));
        Font miniLineFont = Font.createFont(Font.TRUETYPE_FONT, new File("MiniLine2.ttf"));
        Font miniLineFontDerived = miniLineFont.deriveFont(8f);
        Font habboFontDerived = habboFont.deriveFont(16f);
        Font arial = new Font("Arial", Font.PLAIN, 12);
        ge.registerFont(habboFont);
        ge.registerFont(miniLineFont);

        Graphics2D g = (Graphics2D)img.getGraphics();
        //g.setColor(Color.BLACK);
        //g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 8));
        //g.fillRect(0, 0, img.getWidth(), img.getHeight());

//        FontRenderer renderer = new FontRenderer(Font5x7.BITMAPS, Font5x7.GLYPHS, Font5x7.FIRST_CHAR);
//        renderer.drawText(g, "HELLO WORLD!", 0, 10, Color.GREEN);
        g.setColor(Color.GREEN);
        g.setFont(habboFontDerived);
        g.drawString(text, 0, 8);

        //g.setFont(miniLineFontDerived);
        g.drawString("€1234560", 0, 17);

        ImageIO.write(img, "png", new File("output_font.png"));
    }
}
