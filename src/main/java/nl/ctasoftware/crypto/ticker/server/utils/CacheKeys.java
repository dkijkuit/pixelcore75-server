package nl.ctasoftware.crypto.ticker.server.utils;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Content-digest cache keys for the {@code @Cacheable} screen caches. The default
 * Spring key is the whole config record — for IMAGE/ANIMATION screens that means
 * hashing/equals-ing multi-MB base64 strings on every cache lookup and holding the
 * record alive via the key. A hex SHA-256 digest is a small, stable key with the
 * same content-addressing semantics. Exposed as a bean so cache key SpEL can call
 * it ({@code @cacheKeys.digest(...)}).
 */
@Component("cacheKeys")
public class CacheKeys {

    public String digest(final String data) {
        return sha256Hex(data == null ? "" : data);
    }

    public String digest(final List<String> items) {
        if (items == null || items.isEmpty()) {
            return sha256Hex("");
        }
        final MessageDigest md = sha256();
        for (final String item : items) {
            final byte[] bytes = (item == null ? "" : item).getBytes(StandardCharsets.UTF_8);
            md.update(bytes);
            md.update((byte) 0x1F); // unit separator: no cross-entry concatenation ambiguity
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static String sha256Hex(final String data) {
        return HexFormat.of().formatHex(
                sha256().digest(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
