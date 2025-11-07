package nl.ctasoftware.crypto.ticker.server.utils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * Splits the 197-country flag spritesheet into individual PNGs, optionally labeling each with
 * its ISO 3166-1 alpha-2 code. Retains the exact pixel size of each tile and uses
 * NEAREST_NEIGHBOR to keep pixel-art crisp.
 * <p>
 * Usage:
 * javac FlagSheetSplitter.java
 * java FlagSheetSplitter \
 * --in "/path/197_flags.png" \
 * --out "./flags_out" \
 * [--codes "./country_codes_197.txt"] \
 * [--no-label] \
 * [--cols 15 --rows 14]
 * <p>
 * Notes:
 * - For WebP sources add TwelveMonkeys ImageIO or convert to PNG beforehand.
 * - Auto grid detection looks for dark gutters; you can override with --cols/--rows.
 */
public class FlagSheetSplitter {

    // 197 entries: 193 UN members + Kosovo (XK), Palestine (PS), Taiwan (TW), Vatican City (VA)
    private static final String[] CODES = new String[]{
            "AF", "AL", "DZ", "AD", "AO", "AG", "AR", "AM", "AU", "AT",
            "AZ", "BS", "BH", "BD", "BB", "BY", "BE", "BZ", "BJ", "BT",
            "BO", "BA", "BW", "BR", "BN", "BG", "BF", "BI", "CV", "KH",
            "CM", "CA", "CF", "TD", "CL", "CN", "CO", "KM", "CG", "CR",
            "CI", "HR", "CU", "CY", "CZ", "CD", "DK", "DJ", "DM", "DO",
            "EC", "EG", "SV", "GQ", "ER", "EE", "SZ", "ET", "FJ", "FI",
            "FR", "GA", "GM", "GE", "DE", "GH", "GR", "GD", "GT", "GN",
            "GW", "GY", "HT", "HN", "HU", "IS", "IN", "ID", "IR", "IQ",
            "IE", "IL", "IT", "JM", "JP", "JO", "KZ", "KE", "KI", "XK",
            "KW", "KG", "LA", "LV", "LB", "LS", "LR", "LY", "LI", "LT",
            "LU", "MG", "MW", "MY", "MV", "ML", "MT", "MH", "MR", "MU",
            "MX", "FM", "MD", "MC", "MN", "ME", "MA", "MZ", "MM", "NA",
            "NR", "NP", "NL", "NZ", "NI", "NE", "NG", "KP", "MK", "NO",
            "OM", "PK", "PW", "PS", "PA", "PG", "PY", "PE", "PH", "PL",
            "PT", "QA", "RO", "RU", "RW", "KN", "LC", "VC", "WS", "SM",
            "ST", "SA", "SN", "RS", "SC", "SL", "SG", "SK", "SI", "SB",
            "SO", "ZA", "KR", "SS", "ES", "LK", "SD", "SR", "SE", "CH",
            "SY", "TW", "TJ", "TZ", "TH", "TL", "TG", "TO", "TT", "TN",
            "TR", "TM", "TV", "UG", "UA", "AE", "GB", "US", "UY", "UZ",
            "VU", "VA", "VE", "VN", "YE", "ZM", "ZW"
    };

    private static class Args {
        Path in;
        Path out;
        Path codes;
        boolean label = true;      // draw ISO code overlay
        Integer cols = null;       // manual grid override
        Integer rows = null;
    }

    public static void main(String[] argv) throws Exception {
        Args a = parseArgs(argv);
        if (a.in == null || a.out == null) {
            usage();
            System.exit(2);
        }

        Files.createDirectories(a.out);

        BufferedImage sheet = ImageIO.read(a.in.toFile());
        if (sheet == null) throw new IOException("Could not read image: " + a.in);

        // Build tile rectangles
        List<Rectangle> cells;
        if (a.cols != null && a.rows != null) {
            cells = uniformGrid(sheet.getWidth(), sheet.getHeight(), a.cols, a.rows);
        } else {
            List<Integer> vDivs = detectDividers(sheet, true);
            List<Integer> hDivs = detectDividers(sheet, false);
            cells = toCells(vDivs, hDivs, sheet.getWidth(), sheet.getHeight());
            // drop empty/grey footer tiles
            cells.removeIf(r -> isMostlyBackground(sheet, r));
        }

        // top-to-bottom, left-to-right
        cells.sort(Comparator.comparingInt((Rectangle r) -> r.y).thenComparingInt(r -> r.x));

        if (cells.size() < 197) {
            throw new IllegalStateException("Found only " + cells.size() + " tiles; expected at least 197. Try --cols 15 --rows 14.");
        }
        if (cells.size() > 197) {
            cells = new ArrayList<>(cells.subList(0, 197));
        }

        // Load codes
        List<String> codes = loadCodes(a.codes);
        if (codes.size() != 197)
            throw new IllegalArgumentException("Need exactly 197 country codes, got " + codes.size());

        for (int i = 0; i < 197; i++) {
            Rectangle r = cells.get(i);
            BufferedImage tile = sheet.getSubimage(r.x + 1, r.y + 1, r.width - 1, r.height - 1);

            // ZERO-resample path if --no-label
            BufferedImage outImg = a.label ? drawCode(tile, codes.get(i).toUpperCase(Locale.ROOT)) : tile;

            String fileName = String.format(Locale.ROOT, "%03d_%s.png", i + 1, codes.get(i).toUpperCase(Locale.ROOT));
            ImageIO.write(outImg, "png", a.out.resolve(fileName).toFile());
        }

        // quick sanity log
        for (int i = 0; i < Math.min(5, cells.size()); i++) {
            Rectangle r = cells.get(i);
            System.out.printf(Locale.ROOT, "tile %03d -> %dx%d at (%d,%d)%n", i + 1, r.width, r.height, r.x, r.y);
        }
        System.out.println("Wrote 197 PNGs to: " + a.out.toAbsolutePath());
    }

    private static List<String> loadCodes(Path codesFile) throws IOException {
        if (codesFile != null && Files.exists(codesFile)) {
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(codesFile)) {
                String s = line.trim();
                if (s.isEmpty()) continue;
                for (String part : s.split("[\\s,;]+")) {
                    if (!part.isEmpty()) out.add(part.trim());
                }
            }
            return out;
        }
        return UN_COUNTRIES.keySet().stream().toList();
    }

    /**
     * Draws the ISO code on the bottom-right without changing the image size.
     */
    private static BufferedImage drawCode(BufferedImage src, String code) {
        // nearest-neighbor, no smoothing
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g.drawImage(src, 0, 0, src.getWidth(), src.getHeight(), null);

        int pad = Math.max(1, Math.min(src.getWidth(), src.getHeight()) / 12);
        int fontSize = Math.max(6, (int) Math.round(src.getHeight() * 0.35));
        g.setFont(new Font("Monospaced", Font.BOLD, fontSize));

        FontMetrics fm = g.getFontMetrics();
        int textW = fm.stringWidth(code);
        int textH = fm.getAscent();
        int x = Math.max(pad, src.getWidth() - textW - pad);
        int y = Math.max(textH + pad, src.getHeight() - pad);

        g.setColor(new Color(0, 0, 0, 200));
        g.drawString(code, x + 1, y + 1);
        g.setColor(new Color(255, 255, 255, 240));
        g.drawString(code, x, y);
        g.dispose();
        return out;
    }

    /**
     * Detect vertical or horizontal dark gutter lines.
     */
    private static List<Integer> detectDividers(BufferedImage img, boolean vertical) {
        int w = img.getWidth(), h = img.getHeight();
        List<Integer> divs = new ArrayList<>();
        int target = 0xFF333333; // gutter color
        int tol = 36;            // a bit looser for tiny sheet

        int outer = vertical ? w : h;
        int inner = vertical ? h : w;

        for (int i = 0; i < outer; i++) {
            int match = 0;
            for (int j = 0; j < inner; j++) {
                int rgb = vertical ? img.getRGB(i, j) : img.getRGB(j, i);
                if (near(rgb, target, tol)) match++;
            }
            double frac = match / (double) inner;
            if (frac > 0.70) divs.add(i); // lower threshold for small images
        }

        // coalesce consecutive indices to midpoints
        List<Integer> out = new ArrayList<>();
        int runStart = -1, prev = -1000;
        for (int x : divs) {
            if (runStart == -1) {
                runStart = prev = x;
                continue;
            }
            if (x == prev + 1) {
                prev = x;
                continue;
            }
            out.add((runStart + prev) / 2);
            runStart = prev = x;
        }
        if (runStart != -1) out.add((runStart + prev) / 2);
        return out;
    }

    private static List<Rectangle> toCells(List<Integer> vDivs, List<Integer> hDivs, int w, int h) {
        if (vDivs.isEmpty() || vDivs.get(0) > 0) vDivs.add(0, 0);
        if (vDivs.get(vDivs.size() - 1) < w - 1) vDivs.add(w - 1);
        if (hDivs.isEmpty() || hDivs.get(0) > 0) hDivs.add(0, 0);
        if (hDivs.get(hDivs.size() - 1) < h - 1) hDivs.add(h - 1);

        List<Rectangle> cells = new ArrayList<>();
        for (int r = 0; r < hDivs.size() - 1; r++) {
            int y0 = hDivs.get(r), y1 = hDivs.get(r + 1);
            int ch = y1 - y0 - 1;          // strip 1px gutter
            if (ch <= 0) continue;
            int y = y0 + 1;
            for (int c = 0; c < vDivs.size() - 1; c++) {
                int x0 = vDivs.get(c), x1 = vDivs.get(c + 1);
                int cw = x1 - x0 - 1;
                if (cw <= 0) continue;
                int x = x0 + 1;
                cells.add(new Rectangle(x, y, cw, ch));
            }
        }
        return cells;
    }

    private static boolean isMostlyBackground(BufferedImage img, Rectangle r) {
        int same = 0, total = 0, first = img.getRGB(r.x, r.y);
        double sumBright = 0;
        for (int y = r.y; y < r.y + r.height; y++) {
            for (int x = r.x; x < r.x + r.width; x++) {
                int rgb = img.getRGB(x, y);
                if (rgb == first) same++;
                sumBright += brightness(rgb);
                total++;
            }
        }
        double fracSame = same / (double) total;
        double avgB = sumBright / total;
        return (fracSame > 0.997) || (avgB > 0.98);
    }

    private static double brightness(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
    }

    private static boolean near(int argb, int target, int tol) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        int tr = (target >> 16) & 0xFF, tg = (target >> 8) & 0xFF, tb = target & 0xFF;
        return Math.abs(r - tr) <= tol && Math.abs(g - tg) <= tol && Math.abs(b - tb) <= tol;
    }

    private static void usage() {
        System.err.println("Usage: java FlagSheetSplitter --in <sheet.png|webp> --out <dir> [--codes <codes.txt>] [--no-label] [--cols N --rows M]");
    }

    /**
     * Build a uniform N×M grid of rectangles (no gutters), left-to-right, top-to-bottom.
     */
    private static List<Rectangle> uniformGrid(int W, int H, int cols, int rows) {
        List<Rectangle> cells = new ArrayList<>(cols * rows);
        int cellW = W / cols;
        int cellH = H / rows;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int x = c * cellW;
                int y = r * cellH;
                cells.add(new Rectangle(x, y, cellW, cellH));
            }
        }
        return cells;
    }

    /**
     * Simple CLI arg parser.
     */
    private static Args parseArgs(String[] argv) {
        Args a = new Args();
        for (int i = 0; i < argv.length; i++) {
            String s = argv[i];
            switch (s) {
                case "--in":
                    if (++i >= argv.length) throw new IllegalArgumentException("--in requires a path");
                    a.in = Paths.get(argv[i]);
                    break;
                case "--out":
                    if (++i >= argv.length) throw new IllegalArgumentException("--out requires a path");
                    a.out = Paths.get(argv[i]);
                    break;
                case "--codes":
                    if (++i >= argv.length) throw new IllegalArgumentException("--codes requires a path");
                    a.codes = Paths.get(argv[i]);
                    break;
                case "--no-label":
                    a.label = false;
                    break;
                case "--cols":
                    if (++i >= argv.length) throw new IllegalArgumentException("--cols requires an integer");
                    a.cols = Integer.parseInt(argv[i]);
                    break;
                case "--rows":
                    if (++i >= argv.length) throw new IllegalArgumentException("--rows requires an integer");
                    a.rows = Integer.parseInt(argv[i]);
                    break;
                default:
                    System.err.println("Unknown arg: " + s);
                    usage();
                    System.exit(2);
            }
        }
        if ((a.cols == null) != (a.rows == null)) {
            throw new IllegalArgumentException("Provide both --cols and --rows together.");
        }
        return a;
    }

    public static final Map<String, String> UN_COUNTRIES = new LinkedHashMap<>();

    static {
        UN_COUNTRIES.put("AFG", "Afghanistan");
        UN_COUNTRIES.put("ALB", "Albania");
        UN_COUNTRIES.put("DZA", "Algeria");
        UN_COUNTRIES.put("AND", "Andorra");
        UN_COUNTRIES.put("AGO", "Angola");
        UN_COUNTRIES.put("ATG", "Antigua and Barbuda");
        UN_COUNTRIES.put("ARG", "Argentina");
        UN_COUNTRIES.put("ARM", "Armenia");
        UN_COUNTRIES.put("AUS", "Australia");
        UN_COUNTRIES.put("AUT", "Austria");
        UN_COUNTRIES.put("AZE", "Azerbaijan");
        UN_COUNTRIES.put("BHS", "Bahamas");
        UN_COUNTRIES.put("BHR", "Bahrain");
        UN_COUNTRIES.put("BGD", "Bangladesh");
        UN_COUNTRIES.put("BRB", "Barbados");
        UN_COUNTRIES.put("BLR", "Belarus");
        UN_COUNTRIES.put("BEL", "Belgium");
        UN_COUNTRIES.put("BLZ", "Belize");
        UN_COUNTRIES.put("BEN", "Benin");
        UN_COUNTRIES.put("BTN", "Bhutan");
        UN_COUNTRIES.put("BOL", "Bolivia (Plurinational State of)");
        UN_COUNTRIES.put("BIH", "Bosnia and Herzegovina");
        UN_COUNTRIES.put("BWA", "Botswana");
        UN_COUNTRIES.put("BRA", "Brazil");
        UN_COUNTRIES.put("BRN", "Brunei Darussalam");
        UN_COUNTRIES.put("BGR", "Bulgaria");
        UN_COUNTRIES.put("BFA", "Burkina Faso");
        UN_COUNTRIES.put("BDI", "Burundi");
        UN_COUNTRIES.put("CPV", "Cabo Verde");
        UN_COUNTRIES.put("CMR", "Cameroon");
        UN_COUNTRIES.put("CAN", "Canada");
        UN_COUNTRIES.put("CAF", "Central African Republic");
        UN_COUNTRIES.put("TCD", "Chad");
        UN_COUNTRIES.put("CHL", "Chile");
        UN_COUNTRIES.put("CHN", "China");
        UN_COUNTRIES.put("COL", "Colombia");
        UN_COUNTRIES.put("COM", "Comoros");
        UN_COUNTRIES.put("COG", "Congo");
        UN_COUNTRIES.put("COD", "Congo (Democratic Republic of the)");
        UN_COUNTRIES.put("CRI", "Costa Rica");
        UN_COUNTRIES.put("CIV", "Côte d’Ivoire");
        UN_COUNTRIES.put("HRV", "Croatia");
        UN_COUNTRIES.put("CUB", "Cuba");
        UN_COUNTRIES.put("CYP", "Cyprus");
        UN_COUNTRIES.put("CZE", "Czechia");
        UN_COUNTRIES.put("DNK", "Denmark");
        UN_COUNTRIES.put("DJI", "Djibouti");
        UN_COUNTRIES.put("DMA", "Dominica");
        UN_COUNTRIES.put("DOM", "Dominican Republic");
        UN_COUNTRIES.put("ECU", "Ecuador");
        UN_COUNTRIES.put("EGY", "Egypt");
        UN_COUNTRIES.put("SLV", "El Salvador");
        UN_COUNTRIES.put("GNQ", "Equatorial Guinea");
        UN_COUNTRIES.put("ERI", "Eritrea");
        UN_COUNTRIES.put("EST", "Estonia");
        UN_COUNTRIES.put("SWZ", "Eswatini");
        UN_COUNTRIES.put("ETH", "Ethiopia");
        UN_COUNTRIES.put("FJI", "Fiji");
        UN_COUNTRIES.put("FIN", "Finland");
        UN_COUNTRIES.put("FRA", "France");
        UN_COUNTRIES.put("GAB", "Gabon");
        UN_COUNTRIES.put("GMB", "Gambia");
        UN_COUNTRIES.put("GEO", "Georgia");
        UN_COUNTRIES.put("DEU", "Germany");
        UN_COUNTRIES.put("GHA", "Ghana");
        UN_COUNTRIES.put("GRC", "Greece");
        UN_COUNTRIES.put("GRD", "Grenada");
        UN_COUNTRIES.put("GTM", "Guatemala");
        UN_COUNTRIES.put("GIN", "Guinea");
        UN_COUNTRIES.put("GNB", "Guinea-Bissau");
        UN_COUNTRIES.put("GUY", "Guyana");
        UN_COUNTRIES.put("HTI", "Haiti");
        UN_COUNTRIES.put("HND", "Honduras");
        UN_COUNTRIES.put("HUN", "Hungary");
        UN_COUNTRIES.put("ISL", "Iceland");
        UN_COUNTRIES.put("IND", "India");
        UN_COUNTRIES.put("IDN", "Indonesia");
        UN_COUNTRIES.put("IRN", "Iran (Islamic Republic of)");
        UN_COUNTRIES.put("IRQ", "Iraq");
        UN_COUNTRIES.put("IRL", "Ireland");
        UN_COUNTRIES.put("ISR", "Israel");
        UN_COUNTRIES.put("ITA", "Italy");
        UN_COUNTRIES.put("JAM", "Jamaica");
        UN_COUNTRIES.put("JPN", "Japan");
        UN_COUNTRIES.put("JOR", "Jordan");
        UN_COUNTRIES.put("KAZ", "Kazakhstan");
        UN_COUNTRIES.put("KEN", "Kenya");
        UN_COUNTRIES.put("KIR", "Kiribati");
        UN_COUNTRIES.put("XKX", "Kosovo");
        UN_COUNTRIES.put("PRK", "Korea (Democratic People’s Republic of)");
        UN_COUNTRIES.put("KOR", "Korea (Republic of)");
        UN_COUNTRIES.put("KWT", "Kuwait");
        UN_COUNTRIES.put("KGZ", "Kyrgyzstan");
        UN_COUNTRIES.put("LAO", "Lao People’s Democratic Republic");
        UN_COUNTRIES.put("LVA", "Latvia");
        UN_COUNTRIES.put("LBN", "Lebanon");
        UN_COUNTRIES.put("LSO", "Lesotho");
        UN_COUNTRIES.put("LBR", "Liberia");
        UN_COUNTRIES.put("LBY", "Libya");
        UN_COUNTRIES.put("LIE", "Liechtenstein");
        UN_COUNTRIES.put("LTU", "Lithuania");
        UN_COUNTRIES.put("LUX", "Luxembourg");
        UN_COUNTRIES.put("MDG", "Madagascar");
        UN_COUNTRIES.put("MWI", "Malawi");
        UN_COUNTRIES.put("MYS", "Malaysia");
        UN_COUNTRIES.put("MDV", "Maldives");
        UN_COUNTRIES.put("MLI", "Mali");
        UN_COUNTRIES.put("MLT", "Malta");
        UN_COUNTRIES.put("MHL", "Marshall Islands");
        UN_COUNTRIES.put("MRT", "Mauritania");
        UN_COUNTRIES.put("MUS", "Mauritius");
        UN_COUNTRIES.put("MEX", "Mexico");
        UN_COUNTRIES.put("FSM", "Micronesia (Federated States of)");
        UN_COUNTRIES.put("MDA", "Moldova (Republic of)");
        UN_COUNTRIES.put("MCO", "Monaco");
        UN_COUNTRIES.put("MNG", "Mongolia");
        UN_COUNTRIES.put("MNE", "Montenegro");
        UN_COUNTRIES.put("MAR", "Morocco");
        UN_COUNTRIES.put("MOZ", "Mozambique");
        UN_COUNTRIES.put("MMR", "Myanmar");
        UN_COUNTRIES.put("NAM", "Namibia");
        UN_COUNTRIES.put("NRU", "Nauru");
        UN_COUNTRIES.put("NPL", "Nepal");
        UN_COUNTRIES.put("NLD", "Netherlands");
        UN_COUNTRIES.put("NZL", "New Zealand");
        UN_COUNTRIES.put("NIC", "Nicaragua");
        UN_COUNTRIES.put("KHM", "Cambodia");
        UN_COUNTRIES.put("NGA", "Nigeria");
        UN_COUNTRIES.put("NER", "Niger");
        UN_COUNTRIES.put("MKD", "North Macedonia");
        UN_COUNTRIES.put("NOR", "Norway");
        UN_COUNTRIES.put("OMN", "Oman");
        UN_COUNTRIES.put("PAK", "Pakistan");
        UN_COUNTRIES.put("PLW", "Palau");
        UN_COUNTRIES.put("PSE", "Palestine (State of)");
        UN_COUNTRIES.put("PAN", "Panama");
        UN_COUNTRIES.put("PNG", "Papua New Guinea");
        UN_COUNTRIES.put("PRY", "Paraguay");
        UN_COUNTRIES.put("PER", "Peru");
        UN_COUNTRIES.put("PHL", "Philippines");
        UN_COUNTRIES.put("POL", "Poland");
        UN_COUNTRIES.put("PRT", "Portugal");
        UN_COUNTRIES.put("QAT", "Qatar");
        UN_COUNTRIES.put("ROU", "Romania");
        UN_COUNTRIES.put("RUS", "Russian Federation");
        UN_COUNTRIES.put("RWA", "Rwanda");
        UN_COUNTRIES.put("KNA", "Saint Kitts and Nevis");
        UN_COUNTRIES.put("LCA", "Saint Lucia");
        UN_COUNTRIES.put("VCT", "Saint Vincent and the Grenadines");
        UN_COUNTRIES.put("WSM", "Samoa");
        UN_COUNTRIES.put("SMR", "San Marino");
        UN_COUNTRIES.put("STP", "Sao Tome and Principe");
        UN_COUNTRIES.put("SAU", "Saudi Arabia");
        UN_COUNTRIES.put("SEN", "Senegal");
        UN_COUNTRIES.put("SRB", "Serbia");
        UN_COUNTRIES.put("SYC", "Seychelles");
        UN_COUNTRIES.put("SLE", "Sierra Leone");
        UN_COUNTRIES.put("SGP", "Singapore");
        UN_COUNTRIES.put("SVK", "Slovakia");
        UN_COUNTRIES.put("SVN", "Slovenia");
        UN_COUNTRIES.put("SLB", "Solomon Islands");
        UN_COUNTRIES.put("SOM", "Somalia");
        UN_COUNTRIES.put("ZAF", "South Africa");
        UN_COUNTRIES.put("SSD", "South Sudan");
        UN_COUNTRIES.put("ESP", "Spain");
        UN_COUNTRIES.put("LKA", "Sri Lanka");
        UN_COUNTRIES.put("SDN", "Sudan");
        UN_COUNTRIES.put("SUR", "Suriname");
        UN_COUNTRIES.put("SWE", "Sweden");
        UN_COUNTRIES.put("CHE", "Switzerland");
        UN_COUNTRIES.put("SYR", "Syrian Arab Republic");
        UN_COUNTRIES.put("TWM", "Taiwan");
        UN_COUNTRIES.put("TJK", "Tajikistan");
        UN_COUNTRIES.put("TZA", "Tanzania (United Republic of)");
        UN_COUNTRIES.put("THA", "Thailand");
        UN_COUNTRIES.put("TLS", "Timor-Leste");
        UN_COUNTRIES.put("TGO", "Togo");
        UN_COUNTRIES.put("TON", "Tonga");
        UN_COUNTRIES.put("TTO", "Trinidad and Tobago");
        UN_COUNTRIES.put("TUN", "Tunisia");
        UN_COUNTRIES.put("TUR", "Türkiye");
        UN_COUNTRIES.put("TKM", "Turkmenistan");
        UN_COUNTRIES.put("TUV", "Tuvalu");
        UN_COUNTRIES.put("UGA", "Uganda");
        UN_COUNTRIES.put("UKR", "Ukraine");
        UN_COUNTRIES.put("ARE", "United Arab Emirates");
        UN_COUNTRIES.put("GBR", "United Kingdom of Great Britain and Northern Ireland");
        UN_COUNTRIES.put("USA", "United States of America");
        UN_COUNTRIES.put("URY", "Uruguay");
        UN_COUNTRIES.put("UZB", "Uzbekistan");
        UN_COUNTRIES.put("VUT", "Vanuatu");
        UN_COUNTRIES.put("VAT", "Vatican City");
        UN_COUNTRIES.put("VEN", "Venezuela (Bolivarian Republic of)");
        UN_COUNTRIES.put("VNM", "Viet Nam");
        UN_COUNTRIES.put("YEM", "Yemen");
        UN_COUNTRIES.put("ZMB", "Zambia");
        UN_COUNTRIES.put("ZWE", "Zimbabwe");
    }
}
