package nl.ctasoftware.crypto.ticker.server.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CacheKeysTests {

    private final CacheKeys cacheKeys = new CacheKeys();

    @Test
    void sameStringContentGivesSameDigest() {
        assertEquals(cacheKeys.digest("data:image/png;base64,AAA="),
                cacheKeys.digest("data:image/png;base64,AAA="));
    }

    @Test
    void differentStringContentGivesDifferentDigest() {
        assertNotEquals(cacheKeys.digest("A"), cacheKeys.digest("B"));
    }

    @Test
    void stringDigestIsNullSafe() {
        assertEquals(cacheKeys.digest((String) null), cacheKeys.digest(""));
    }

    @Test
    void sameFrameListGivesSameDigest() {
        final List<String> frames = List.of("frame-1", "frame-2", "frame-3");
        assertEquals(cacheKeys.digest(frames), cacheKeys.digest(List.of("frame-1", "frame-2", "frame-3")));
    }

    @Test
    void frameListDigestDependsOnBoundaries() {
        // ["AB","C"] must not collide with ["A","BC"]
        assertNotEquals(cacheKeys.digest(List.of("AB", "C")), cacheKeys.digest(List.of("A", "BC")));
    }

    @Test
    void frameListDigestIsStableAcrossDifferentListImplementations() {
        assertEquals(cacheKeys.digest(List.of("a", "b")), cacheKeys.digest(java.util.Arrays.asList("a", "b")));
    }
}
