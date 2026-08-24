package nl.ctasoftware.crypto.ticker.server.service.job;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Deterministic uploadId for animation uploads: SHA-256 over exactly the content that makes
 * up an upload — frameCount (u32 LE), frameDelayMs (u16 LE), flags (u8, masking out the
 * stage-only bit and the codec bits, i.e. {@code & ~0x07} — both are transport metadata, so
 * staged/inline and v2/RAW uploads of identical content must hash identically or the
 * acked-cache map would thrash between ids and the RAW fallback could not reuse the id),
 * then every frame's raw RGB565 payload bytes in order (the ANIF bodies without their
 * magic/index header) —
 * truncated to the first 4 digest bytes (LE) so it fits the protocol's u32 uploadId field.
 * Because the id is derived from content, the panel can treat it as a per-slot content
 * fingerprint: an unchanged animation keeps its id across cycles and re-uploads can be
 * skipped in favor of a 9-byte /anim/play. Zero is reserved ("no hash") and never emitted.
 */
final class UploadIdHasher {

    static final long NO_HASH = 0L;

    private UploadIdHasher() {
    }

    static long contentHash(final int frameCount, final int frameDelayMs, final int flags,
                            final List<byte[]> framePayloads) {
        final MessageDigest digest = sha256();
        final byte[] header = new byte[7];
        header[0] = (byte) frameCount;
        header[1] = (byte) (frameCount >> 8);
        header[2] = (byte) (frameCount >> 16);
        header[3] = (byte) (frameCount >> 24);
        header[4] = (byte) frameDelayMs;
        header[5] = (byte) (frameDelayMs >> 8);
        header[6] = (byte) (flags & ~(PanelScreenJob.ANIM_FLAG_STAGE_ONLY | PanelScreenJob.ANIM_CODEC_MASK));
        digest.update(header);
        for (final byte[] payload : framePayloads) {
            digest.update(payload);
        }
        return uploadIdFrom(digest.digest());
    }

    /** First 4 digest bytes as u32 LE; a zero result is bumped to 1 (0 is reserved). */
    static long uploadIdFrom(final byte[] digest) {
        final long uploadId = (digest[0] & 0xFFL)
                | (digest[1] & 0xFFL) << 8
                | (digest[2] & 0xFFL) << 16
                | (digest[3] & 0xFFL) << 24;
        return uploadId == NO_HASH ? 1L : uploadId;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest not available", e);
        }
    }
}
