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

import net.william278.husksync.HuskSync;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

/**
 * A {@link GsonAdapter} that additionally compresses data using Snappy.
 * <p>
 * Compression is part of the on-disk/on-wire data format: a snapshot written compressed can only
 * be read back through a Snappy codec. To keep that format readable on every platform - including
 * environments where the native {@code snappy-java} binary cannot be loaded (e.g. it is missing for
 * the OS/arch, or cannot be extracted to a no-exec temp directory) - this adapter transparently
 * falls back to a bundled pure-Java Snappy codec. Both codecs read and write the same Snappy raw
 * block format, so compressed data stays compatible across servers regardless of which produced it.
 * <p>
 * Crucially, this means a server that cannot use the native library never silently degrades to the
 * uncompressed {@link GsonAdapter}, which would be unable to read existing compressed snapshots and
 * could overwrite them with corrupt/empty data.
 */
public class SnappyGsonAdapter extends GsonAdapter {

    // Whether the native snappy-java library is usable on this server. When false, the pure-Java
    // org.iq80.snappy codec (verified usable at construction time) is used instead.
    private final boolean nativeAvailable;

    public SnappyGsonAdapter(@NotNull HuskSync plugin) {
        super(plugin);
        this.nativeAvailable = canUseNativeSnappy(plugin);
    }

    // Probe the native snappy-java library once. If it cannot be loaded, verify the pure-Java
    // fallback works and use that. If neither codec is usable, throw so the plugin fails to load
    // loudly rather than running in a state that cannot read or write compressed data.
    private static boolean canUseNativeSnappy(@NotNull HuskSync plugin) {
        try {
            org.xerial.snappy.Snappy.uncompress(org.xerial.snappy.Snappy.compress(new byte[0]));
            return true;
        } catch (Throwable nativeError) {
            try {
                final byte[] probe = org.iq80.snappy.Snappy.compress(new byte[0]);
                org.iq80.snappy.Snappy.uncompress(probe, 0, probe.length);
            } catch (Throwable fallbackError) {
                throw new IllegalStateException(
                        "Neither the native nor the pure-Java Snappy codec could be initialized, so "
                                + "HuskSync cannot read or write compressed data. Resolve the Snappy library "
                                + "issue, or set 'compress-data: false' in config.yml (existing compressed data "
                                + "must be migrated on a server where Snappy works before doing so).",
                        fallbackError);
            }
            plugin.log(Level.WARNING, ("The native Snappy library could not be initialized (%s: %s); "
                    + "using the bundled pure-Java Snappy codec instead. Compressed data remains fully "
                    + "compatible across servers, so no migration is required.").formatted(
                    nativeError.getClass().getSimpleName(), nativeError.getMessage()));
            return false;
        }
    }

    @Override
    public <A extends Adaptable> byte[] toBytes(@NotNull A data) throws AdaptionException {
        return compress(super.toBytes(data));
    }

    @NotNull
    @Override
    public <A extends Adaptable> A fromBytes(byte[] data, @NotNull Class<A> type) throws AdaptionException {
        return super.fromBytes(uncompress(data), type);
    }

    @Override
    @NotNull
    public String bytesToString(byte[] bytes) {
        return super.bytesToString(uncompress(bytes));
    }

    @NotNull
    private byte[] compress(byte[] uncompressed) throws AdaptionException {
        try {
            return nativeAvailable
                    ? org.xerial.snappy.Snappy.compress(uncompressed)
                    : org.iq80.snappy.Snappy.compress(uncompressed);
        } catch (Throwable e) {
            throw new AdaptionException("Failed to compress data through Snappy", e);
        }
    }

    @NotNull
    private byte[] uncompress(byte[] compressed) throws AdaptionException {
        try {
            return nativeAvailable
                    ? org.xerial.snappy.Snappy.uncompress(compressed)
                    : org.iq80.snappy.Snappy.uncompress(compressed, 0, compressed.length);
        } catch (Throwable e) {
            throw new AdaptionException("Failed to decompress data through Snappy", e);
        }
    }

}
