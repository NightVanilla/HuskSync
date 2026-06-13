/*
 * This file is part of HuskSync, licensed under the Apache License 2.0.
 *
 *  Copyright (c) William278 <will27528@gmail.com>
 *  Copyright (c) contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.william278.husksync.adapter;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * Verifies the uncompressed-snapshot detection that lets {@link SnappyGsonAdapter} recover snapshots
 * stored without Snappy compression (e.g. while 'compress-data' was off, or by an older build that
 * fell back to the plain GsonAdapter) instead of failing the data load with FAILED_TO_UNCOMPRESS.
 */
@DisplayName("Snappy Gson Adapter Tests")
public class SnappyGsonAdapterTests {

    @Test
    @DisplayName("Detects uncompressed JSON snapshots")
    public void detectsUncompressedJson() {
        Assertions.assertTrue(SnappyGsonAdapter.looksLikeUncompressedJson(utf8(sampleSnapshotJson())),
                "A raw JSON object snapshot should be detected as uncompressed");
        Assertions.assertTrue(SnappyGsonAdapter.looksLikeUncompressedJson(utf8("   \n\t{\"a\":1}")),
                "Leading whitespace before the JSON object should be tolerated");
        Assertions.assertTrue(SnappyGsonAdapter.looksLikeUncompressedJson(utf8("[1,2,3]")),
                "A raw JSON array should be detected as uncompressed");
    }

    @Test
    @DisplayName("Does not mistake Snappy-compressed data for uncompressed")
    public void doesNotMistakeCompressedData() throws Exception {
        final byte[] json = utf8(sampleSnapshotJson());
        Assertions.assertFalse(
                SnappyGsonAdapter.looksLikeUncompressedJson(org.xerial.snappy.Snappy.compress(json)),
                "Natively Snappy-compressed data must never be treated as uncompressed JSON");
        Assertions.assertFalse(
                SnappyGsonAdapter.looksLikeUncompressedJson(org.iq80.snappy.Snappy.compress(json)),
                "Pure-Java Snappy-compressed data must never be treated as uncompressed JSON");
    }

    @Test
    @DisplayName("Native and pure-Java codecs are wire-compatible")
    public void crossCodecRoundTripIsCompatible() throws Exception {
        final byte[] original = utf8(sampleSnapshotJson());

        // The fallback's safety guarantee: a snapshot compressed by one codec must decompress
        // correctly through the other, so a server that can only use the pure-Java codec can still
        // read data written natively (and vice versa) without corruption. This holds only while both
        // libraries use the same Snappy raw block format; the round-trips below guard against a future
        // version bump to an incompatible format (e.g. the Snappy framing format) silently breaking it.
        final byte[] nativeCompressed = org.xerial.snappy.Snappy.compress(original);
        Assertions.assertArrayEquals(original,
                org.iq80.snappy.Snappy.uncompress(nativeCompressed, 0, nativeCompressed.length),
                "Data compressed by native snappy-java must decompress through the pure-Java codec");

        final byte[] iq80Compressed = org.iq80.snappy.Snappy.compress(original);
        Assertions.assertArrayEquals(original,
                org.xerial.snappy.Snappy.uncompress(iq80Compressed),
                "Data compressed by the pure-Java codec must decompress through native snappy-java");
    }

    @Test
    @DisplayName("Does not treat corrupt or empty data as uncompressed")
    public void doesNotRecoverCorruptData() {
        Assertions.assertFalse(SnappyGsonAdapter.looksLikeUncompressedJson(new byte[]{0x42, 0x00, (byte) 0xff}),
                "Corrupt, non-JSON bytes should not be treated as uncompressed JSON");
        Assertions.assertFalse(SnappyGsonAdapter.looksLikeUncompressedJson(new byte[0]),
                "Empty data should not be treated as uncompressed JSON");
        Assertions.assertFalse(SnappyGsonAdapter.looksLikeUncompressedJson(utf8("   \n ")),
                "All-whitespace data should not be treated as uncompressed JSON");
    }

    @NotNull
    private static byte[] utf8(@NotNull String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @NotNull
    private static String sampleSnapshotJson() {
        final StringBuilder sb = new StringBuilder("{\"format_version\":4,\"data\":{");
        for (int i = 0; i < 64; i++) {
            sb.append("\"key").append(i).append("\":\"value-").append(i).append("\",");
        }
        return sb.append("\"last\":\"value\"}}").toString();
    }

}
