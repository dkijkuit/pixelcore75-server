package nl.ctasoftware.crypto.ticker.server.service.image;

import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;

@Service
public class PaintToolsService {
    final Font habboFont8Px;
    final Font miniLineFont8Px;
    final Font ledBoardFont8Px;

    public PaintToolsService(final Font habboFont8Px, final Font miniLineFont8Px, Font ledBoardFont8Px) {
        this.habboFont8Px = habboFont8Px;
        this.miniLineFont8Px = miniLineFont8Px;
        this.ledBoardFont8Px = ledBoardFont8Px;
    }

    public BufferedImage newImage() {
        return new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
    }

    public void drawTextAlignRight(final BufferedImage img, final Font font, final String text, final int y, final Color color) {
        final Graphics g = img.getGraphics();
        final FontMetrics fontMetrics = g.getFontMetrics(font);
        final int x = img.getWidth() - (int) fontMetrics.getStringBounds(text, g).getWidth();
        g.setColor(color);
        g.setFont(font);
        g.drawString(LatinFoldService.fold(text), x, y);
    }

    public void drawImage(final BufferedImage img, final BufferedImage imageToDraw, final int x, final int y) {
        final Graphics g = img.getGraphics();
        g.drawImage(imageToDraw, x, y, null);
    }

    public void drawTextAlignCenter(final BufferedImage img, final Font font, final String text, final int y, final Color color) {
        final Graphics g = img.getGraphics();
        final FontMetrics fontMetrics = g.getFontMetrics(font);
        final int x = (img.getWidth() / 2) - ((int) fontMetrics.getStringBounds(text, g).getWidth() / 2);
        g.setColor(color);
        g.setFont(font);
        g.drawString(LatinFoldService.fold(text), x, y);
    }

    public void drawText(final BufferedImage img, final Font font, final String text, final int x, final int y, final Color color) {
        final Graphics g = img.getGraphics();
        g.setColor(color);
        g.setFont(font);
        g.drawString(LatinFoldService.fold(text), x, y);
    }

    public void drawSparkLine(final BufferedImage img, int startX, int startY, int endY, final Color lineColor, final Color sparkColor) {
        final Graphics2D g = (Graphics2D) img.getGraphics();
        g.setColor(lineColor);
        g.drawLine(startX, startY, startX, endY);
        img.setRGB(startX, endY, sparkColor.getRGB());
    }

    public void drawLine(final BufferedImage img, int startX, int startY, int endY, final Color lineColor, final float width) {
        final Graphics2D g = (Graphics2D) img.getGraphics();
        g.setColor(lineColor);
        g.setStroke(new BasicStroke(width));
        g.drawLine(startX, startY, startX, endY);
    }
}
