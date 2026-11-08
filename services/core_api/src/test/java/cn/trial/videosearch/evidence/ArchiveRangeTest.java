package cn.trial.videosearch.evidence;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveRangeTest {
    @Test void supportsNormalOpenAndSuffixRangesWithoutReadingBeyondFile() {
        assertArrayEquals(new long[]{0, 999}, ArchiveController.parseRange(null, 1000));
        assertArrayEquals(new long[]{10, 19}, ArchiveController.parseRange("bytes=10-19", 1000));
        assertArrayEquals(new long[]{900, 999}, ArchiveController.parseRange("bytes=900-", 1000));
        assertArrayEquals(new long[]{900, 999}, ArchiveController.parseRange("bytes=-100", 1000));
        assertArrayEquals(new long[]{0, 999}, ArchiveController.parseRange("bytes=-5000", 1000));
        assertArrayEquals(new long[]{990, 999}, ArchiveController.parseRange("bytes=990-5000", 1000));
    }
    @Test void rejectsInvalidAndMultipleRanges() {
        assertNull(ArchiveController.parseRange("bytes=1000-", 1000));
        assertNull(ArchiveController.parseRange("bytes=20-10", 1000));
        assertNull(ArchiveController.parseRange("bytes=-0", 1000));
        assertNull(ArchiveController.parseRange("bytes=0-1,4-6", 1000));
        assertNull(ArchiveController.parseRange("bytes=--", 1000));
    }
}
