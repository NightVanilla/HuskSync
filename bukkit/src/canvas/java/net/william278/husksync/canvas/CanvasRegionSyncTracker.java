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

package net.william278.husksync.canvas;

import io.canvasmc.canvas.region.RegionThreadingTickManager;
import io.canvasmc.canvas.region.RegionTickData;
import io.canvasmc.canvas.region.WorldRegionizer;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.william278.husksync.BukkitHuskSync;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Tracks HuskSync activity (snapshots, respawns, region crossings) per ticking region, using Canvas'
 * regionized data API ({@code Server#createRegionizedData}/{@code Server#getLocalRegionizedData}).
 * <p>
 * Region-local statistics follow regions as they merge, and are logged alongside region state
 * (chunk count, tick handle state) when debug logging is enabled. Server-wide rollup counters are
 * kept separately, as region-local data must never be read across region boundaries.
 */
public class CanvasRegionSyncTracker {

    private final BukkitHuskSync plugin;
    private final RegionTickData.IRegionizedData<RegionSyncStats> regionData;

    // Server-wide rollups; region-local stats may only be read on their owning region thread
    private final AtomicLong totalSnapshots = new AtomicLong();
    private final AtomicLong totalRespawns = new AtomicLong();
    private final AtomicLong totalRegionCrossings = new AtomicLong();

    public CanvasRegionSyncTracker(@NotNull BukkitHuskSync plugin) {
        this.plugin = plugin;
        this.regionData = plugin.getServer().createRegionizedData(
                RegionSyncStats::new,
                new RegionSyncStats.Callback()
        );
    }

    public void recordSnapshot(@NotNull Player player) {
        totalSnapshots.incrementAndGet();
        withLocalStats(player, stats -> stats.snapshots++);
    }

    public void recordRespawn(@NotNull Player player) {
        totalRespawns.incrementAndGet();
        withLocalStats(player, stats -> stats.respawns++);
    }

    public void recordRegionCrossing(@NotNull Player player) {
        totalRegionCrossings.incrementAndGet();
        withLocalStats(player, stats -> stats.crossings++);
    }

    /**
     * Get a server-wide summary of tracked Canvas sync activity
     *
     * @return a human-readable summary line
     */
    @NotNull
    public String getSummary() {
        return "snapshots: %d, respawns: %d, region crossings: %d".formatted(
                totalSnapshots.get(), totalRespawns.get(), totalRegionCrossings.get());
    }

    // Update the stats local to the region that owns the player, if called on its owning thread
    private void withLocalStats(@NotNull Player player, @NotNull Consumer<RegionSyncStats> consumer) {
        final Server server = plugin.getServer();
        if (server.isGlobalTickThread() || !server.isOwnedByCurrentRegion(player)) {
            return;
        }
        try {
            final RegionSyncStats stats = server.getLocalRegionizedData(regionData);
            consumer.accept(stats);
            if (plugin.getSettings().isDebugLogging()) {
                plugin.debug("[Canvas] %s @ %s".formatted(player.getName(), stats.describe()));
            }
        } catch (IllegalStateException e) {
            // Thrown if the current thread does not own a region; nothing to record
        }
    }

    /**
     * HuskSync statistics local to a single ticking region
     */
    static final class RegionSyncStats {

        private final RegionTickData tickData;
        private final World world;
        private long snapshots;
        private long respawns;
        private long crossings;

        private RegionSyncStats(@NotNull RegionTickData tickData, @NotNull World world) {
            this.tickData = tickData;
            this.world = world;
        }

        @NotNull
        String describe() {
            final WorldRegionizer.ChunkRegion region = tickData.getRegion();
            final RegionThreadingTickManager.RegionHandle handle = tickData.getTickManager();
            return ("region[world=%s, state=%s, chunks=%d, tickingGameElements=%s, sprinting=%s, "
                    + "snapshots=%d, respawns=%d, crossings=%d]").formatted(
                    world.getName(), region.getState(), region.getOwnedPackedChunkPositions().length,
                    handle.doesRunGameElements(), handle.isSprinting(),
                    snapshots, respawns, crossings
            );
        }

        // Merge/split callbacks run under critical region locks; they must not block or throw
        static final class Callback implements RegionTickData.IRegionizedData.IRegionizedCallback<RegionSyncStats> {

            @Override
            public void merge(@NotNull RegionSyncStats from, @NotNull RegionSyncStats into, long fromTickOffset) {
                into.snapshots += from.snapshots;
                into.respawns += from.respawns;
                into.crossings += from.crossings;
            }

            @Override
            public void split(@NotNull RegionSyncStats from, int chunkToRegionShift,
                              @NotNull Long2ReferenceOpenHashMap<RegionSyncStats> regionToData,
                              @NotNull ReferenceOpenHashSet<RegionSyncStats> dataSet) {
                // Counters cannot be meaningfully apportioned between split regions; each new
                // region instead lazily recreates its stats from zero
            }

        }

    }

}
