package nl.ctasoftware.crypto.ticker.server.service.command;

import org.junit.jupiter.api.Assertions;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Golden-snapshot support for the ACMD command screens (the frame-path oracle is gone —
 * the command batch is the only path): reduces an {@link AcmdMirror} frame to the panel's
 * RGB565 view and pins it via a recorded SHA-256 digest. Regenerate the recorded values
 * with {@code -Dpixelcore75.golden.record=true} (prints the new digests; review the diff
 * before committing — a changed digest is a rendering change).
 *
 * <p>Determinism rests on the pixel fonts' integer advances and the extractor's alpha
 * threshold, which held byte-exact across engines before (the old parity budgets only
 * guarded JDK font variance).
 */
public final class CommandGolden {

    public static final int PIXELS = 64 * 32;

    private CommandGolden() {
    }

    /** Panel-view RGB565 (row-major 64&times;32 u16 values as ints) of the mirror at {@code t}. */
    public static int[] frameAt(final byte[] batch, final long elapsedMs) {
        return AcmdMirror.parse(batch).frameAt(elapsedMs);
    }

    /** SHA-256 over the u16 pixel values, little-endian — stable across JVM runs. */
    public static String digest(final int[] frame) {
        if (frame.length != PIXELS) {
            throw new IllegalArgumentException("frame must hold " + PIXELS + " pixels");
        }
        final byte[] bytes = new byte[PIXELS * 2];
        for (int i = 0; i < PIXELS; i++) {
            final int v = frame[i] & 0xFFFF;
            bytes[i * 2] = (byte) v;
            bytes[i * 2 + 1] = (byte) (v >> 8);
        }
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Asserts the frame matches the recorded golden digest for {@code name}. */
    public static void assertGolden(final String name, final int[] actual) {
        final String actualHex = digest(actual);
        if (Boolean.getBoolean("pixelcore75.golden.record")
                || "true".equalsIgnoreCase(System.getenv("PIXELCORE75_GOLDEN_RECORD"))) {
            System.out.println("pixelcore75.golden." + name + "=" + actualHex);
            return;
        }
        final String expected = GoldenDigest.forName(name);
        Assertions.assertEquals(expected, actualHex, "golden drift on " + name);
    }
}
