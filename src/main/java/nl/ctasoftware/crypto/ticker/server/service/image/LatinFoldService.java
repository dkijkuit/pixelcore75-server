package nl.ctasoftware.crypto.ticker.server.service.image;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class LatinFoldService {
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    // Map of *non-diacritic* specials and ligatures that normalization won't reduce to ASCII.
    // Keys are code points, not chars (so this works for BMP and beyond).
    private static final Map<Integer, String> EXTRA = new HashMap<>();
    static {
        // German sharp s
        EXTRA.put(0x00DF, "ss"); // ß
        EXTRA.put(0x1E9E, "SS"); // ẞ

        // Latin ligatures
        EXTRA.put(0x00C6, "AE"); // Æ
        EXTRA.put(0x00E6, "ae"); // æ
        EXTRA.put(0x0152, "OE"); // Œ
        EXTRA.put(0x0153, "oe"); // œ
        EXTRA.put(0x0132, "IJ"); // Ĳ (Dutch)
        EXTRA.put(0x0133, "ij"); // ĳ

        // Scandinavian & others not reduced by removing diacritics alone
        EXTRA.put(0x00D8, "O");  // Ø
        EXTRA.put(0x00F8, "o");  // ø
        EXTRA.put(0x0141, "L");  // Ł
        EXTRA.put(0x0142, "l");  // ł
        EXTRA.put(0x0110, "D");  // Đ
        EXTRA.put(0x0111, "d");  // đ
        EXTRA.put(0x00D0, "D");  // Ð (Eth)
        EXTRA.put(0x00F0, "d");  // ð
        EXTRA.put(0x00DE, "Th"); // Þ (Thorn)
        EXTRA.put(0x00FE, "th"); // þ

        // Misc letters sometimes wanted as ASCII
        EXTRA.put(0x014A, "N");  // Ŋ
        EXTRA.put(0x014B, "n");  // ŋ
        EXTRA.put(0x0138, "k");  // ĸ (kra)
        EXTRA.put(0x0166, "T");  // Ŧ
        EXTRA.put(0x0167, "t");  // ŧ
    }

    private LatinFoldService() {}

    /**
     * Replaces letters with diacritics and common special variants/ligatures
     * by their basic Latin equivalents (ASCII). Keeps digits and punctuation intact.
     *
     * Examples:
     *   fold("Crème brûlée & Weißbier, Łódź, Øresund, Œuvre") ->
     *       "Creme brulee & Weissbier, Lodz, Oresund, OEuvre"
     *   fold("Straße → ẞTRASSE") -> "Strasse → SSTRASSE"
     */
    public static String fold(String input) {
        Objects.requireNonNull(input, "input");

        // 1) Compatibility-decompose (splits accents and many width/compat forms).
        String decomposed = Normalizer.normalize(input, Normalizer.Form.NFKD);

        // 2) Drop all combining marks (accents, tildes, cedillas, etc.).
        String noMarks = COMBINING_MARKS.matcher(decomposed).replaceAll("");

        // 3) Walk code points; map any remaining non-ASCII letters/ligatures we care about.
        StringBuilder out = new StringBuilder(noMarks.length());
        for (int i = 0; i < noMarks.length();) {
            int cp = noMarks.codePointAt(i);
            i += Character.charCount(cp);

            if (cp <= 0x7F) { // already ASCII
                out.append((char) cp);
                continue;
            }

            String mapped = EXTRA.get(cp);
            if (mapped != null) {
                out.append(mapped);
            } else {
                // Fallback: drop remaining diacritic-like artifacts to closest ASCII letter if possible,
                // else keep as-is or replace with '?' depending on your needs.
                // Here we choose to KEEP characters we don't explicitly map.
                out.appendCodePoint(cp);
            }
        }
        return out.toString();
    }

    // Convenience: strict ASCII version that replaces any remaining non-ASCII with '?'
    public static String foldToAsciiStrict(String input) {
        String folded = fold(input);
        StringBuilder out = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length();) {
            int cp = folded.codePointAt(i);
            i += Character.charCount(cp);
            out.append(cp <= 0x7F ? (char) cp : '?');
        }
        return out.toString();
    }
}
