package nl.ctasoftware.crypto.ticker.server.service.job;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadIdHasherTests {

    private static final List<byte[]> TWO_FRAMES = List.of(
            new byte[]{1, 2, 3, 4},
            new byte[]{5, 6, 7, 8});

    @Test
    void deterministicAcrossRepeatedCalls() {
        final long first = UploadIdHasher.contentHash(2, 100, 0, TWO_FRAMES);
        for (int i = 0; i < 10; i++) {
            assertEquals(first, UploadIdHasher.contentHash(2, 100, 0, TWO_FRAMES));
        }
    }

    @Test
    void differsOnFrameCountEvenWithSameTotalBytes() {
        final long split = UploadIdHasher.contentHash(2, 100, 0, List.of(new byte[]{1, 2}, new byte[]{3, 4}));
        final long single = UploadIdHasher.contentHash(1, 100, 0, List.of(new byte[]{1, 2, 3, 4}));
        assertNotEquals(split, single);
    }

    @Test
    void differsOnFrameDelayMs() {
        assertNotEquals(UploadIdHasher.contentHash(2, 100, 0, TWO_FRAMES),
                UploadIdHasher.contentHash(2, 101, 0, TWO_FRAMES));
    }

    @Test
    void stageOnlyFlagDoesNotChangeHash() {
        assertEquals(UploadIdHasher.contentHash(2, 100, 0, TWO_FRAMES),
                UploadIdHasher.contentHash(2, 100, PanelScreenJob.ANIM_FLAG_STAGE_ONLY, TWO_FRAMES));
    }

    @Test
    void codecBitsDoNotChangeHash() {
        final long raw = UploadIdHasher.contentHash(2, 100, 0, TWO_FRAMES);
        assertEquals(raw, UploadIdHasher.contentHash(2, 100, PanelScreenJob.ANIM_CODEC_PAL_RLE, TWO_FRAMES));
        assertEquals(raw, UploadIdHasher.contentHash(2, 100,
                PanelScreenJob.ANIM_FLAG_STAGE_ONLY | PanelScreenJob.ANIM_CODEC_PAL_RLE, TWO_FRAMES));
    }

    @Test
    void differsOnNonTransportFlagBits() {
        assertNotEquals(UploadIdHasher.contentHash(2, 100, 0, TWO_FRAMES),
                UploadIdHasher.contentHash(2, 100, 0x08, TWO_FRAMES));
    }

    @Test
    void differsOnAnyFrameByte() {
        assertNotEquals(UploadIdHasher.contentHash(2, 100, 0, TWO_FRAMES),
                UploadIdHasher.contentHash(2, 100, 0, List.of(new byte[]{1, 2, 3, 4}, new byte[]{5, 6, 7, 9})));
    }

    @Test
    void resultIsAU32AndNeverZero() {
        for (int i = 0; i < 100; i++) {
            final long uploadId = UploadIdHasher.contentHash(2, i, i % 2, List.of(
                    new byte[]{(byte) i, 0, 0, 0}, new byte[]{0, (byte) i, 0, 0}));
            assertTrue(uploadId > 0, "uploadId must be a positive u32");
            assertTrue(uploadId <= 0xFFFFFFFFL, "uploadId must fit in an unsigned 32-bit int");
        }
    }

    @Test
    void truncationReadsFirstFourDigestBytesLittleEndian() {
        assertEquals(0x04030201L, UploadIdHasher.uploadIdFrom(new byte[]{1, 2, 3, 4, 5, 6}));
        assertEquals(0x00000100L, UploadIdHasher.uploadIdFrom(new byte[]{0, 1, 0, 0}));
    }

    @Test
    void zeroDigestIsReservedAndBumpedToOne() {
        assertEquals(1L, UploadIdHasher.uploadIdFrom(new byte[]{0, 0, 0, 0}));
    }
}
