package nl.ctasoftware.crypto.ticker.server.service.screen.formula1;

import java.awt.*;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class F1TeamUtils {
    private static final Map<String, String> TEAM_HEX;
    static {
        Map<String, String> m = new HashMap<>();

        // McLaren
        m.put("mclaren", "#FF8700");

        // Red Bull Racing
        m.put("redbull", "#3671C6");
        m.put("redbullracing", "#3671C6");

        // Mercedes
        m.put("mercedes", "#00D2BE");
        m.put("mercedesamgpetronas", "#00D2BE");

        // Ferrari
        m.put("ferrari", "#DC0000");
        m.put("scuderiaferrari", "#DC0000");

        // Williams
        m.put("williams", "#37BEDD");
        m.put("williamsracing", "#37BEDD");

        // Sauber (Stake/Kick Sauber era)
        m.put("sauber", "#52E252");
        m.put("stakef1", "#52E252");
        m.put("kicksauber", "#52E252");

        // Haas
        m.put("haas", "#B6BABD");
        m.put("haasf1team", "#B6BABD");

        // Aston Martin
        m.put("astonmartin", "#006F62");
        m.put("astonmartininformulaone", "#006F62");

        // Alpine
        m.put("alpine", "#2293D1");
        m.put("alpinef1team", "#2293D1");
        m.put("bwtalpine", "#2293D1");

        // RB F1 Team (Visa Cash App RB)
        m.put("rb", "#1434CB");
        m.put("rbf1team", "#1434CB");
        m.put("visacashapprb", "#1434CB");
        m.put("vcarb", "#1434CB");

        TEAM_HEX = Map.copyOf(m);
    }

    private static String normalizeTeamName(String raw) {
        if (raw == null) return "";
        String noDiacritics = Normalizer.normalize(raw, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "");
        return noDiacritics
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", ""); // drop spaces, punctuation, etc.
    }

    /** Returns a hex color (e.g. "#DC0000") for a constructor/team name. */
    public static String constructorColorHex(String constructorName) {
        String key = normalizeTeamName(constructorName);
        return TEAM_HEX.getOrDefault(key, "#777777"); // fallback grey
    }

    /** Returns a java.awt.Color for a constructor/team name. */
    public static Color constructorColor(String constructorName) {
        return Color.decode(constructorColorHex(constructorName));
    }
}
