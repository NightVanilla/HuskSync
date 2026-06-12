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

import io.canvasmc.canvas.event.EntityPortalAsyncEvent;
import io.canvasmc.canvas.event.EntityPostPortalAsyncEvent;
import io.canvasmc.canvas.event.EntityPostTeleportAsyncEvent;
import io.canvasmc.canvas.event.EntityTeleportAsyncEvent;
import io.canvasmc.canvas.event.PlayerPostRespawnAsyncEvent;
import io.canvasmc.canvas.event.PlayerRespawnAsyncEvent;
import io.canvasmc.canvas.event.PlayerSaveEvent;
import io.canvasmc.canvas.event.PlayerViewEndCreditsEvent;
import io.canvasmc.canvas.event.world.WorldUnloadAsyncEvent;
import io.canvasmc.canvas.region.RegionThreadingTickManager;
import lombok.Getter;
import net.william278.husksync.BukkitHuskSync;
import net.william278.husksync.config.Settings.SynchronizationSettings.CanvasSettings;
import net.william278.husksync.data.DataSnapshot;
import net.william278.husksync.listener.PaperEventListener;
import net.william278.husksync.user.BukkitUser;
import net.william278.husksync.user.OnlineUser;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

/**
 * Canvas (<a href="https://canvasmc.io">canvasmc.io</a>) event listener. Extends the Paper listener with
 * handlers for Canvas' async, region threading-aware events, and tracks per-region sync statistics
 * through a {@link CanvasRegionSyncTracker}.
 * <p>
 * This class references Canvas API types and must only be loaded after
 * {@link CanvasUtil#isCanvasServer()} has returned {@code true}.
 */
public class CanvasEventListener extends PaperEventListener {

    @Getter
    private CanvasRegionSyncTracker regionTracker;

    public CanvasEventListener(@NotNull BukkitHuskSync plugin) {
        super(plugin);
    }

    @Override
    public void onEnable() {
        // Created before events are registered, so handlers never see an uninitialized tracker
        this.regionTracker = new CanvasRegionSyncTracker(getPlugin());
        this.logCanvasPlatform();
        super.onEnable();
    }

    @Override
    public void handlePluginDisable() {
        // The tracker is null if the plugin was disabled before events were enabled
        if (regionTracker != null) {
            getPlugin().log(Level.INFO, "Canvas sync activity this session - " + regionTracker.getSummary());
        }
        super.handlePluginDisable();
    }

    // Log state read from Canvas' region threading tick rate manager
    private void logCanvasPlatform() {
        final RegionThreadingTickManager tickManager = getPlugin().getServer().getRegionThreadingTickRateManager();
        final RegionThreadingTickManager.RegionHandle globalRegion = tickManager.getGlobalRegionHandle();
        getPlugin().log(Level.INFO, String.format(
                "Canvas server detected! Enabled the HuskSync Canvas integration " +
                "(tick rate: %s, global region ticking game elements: %s)",
                tickManager.getTickRate(), globalRegion.doesRunGameElements()
        ));
    }

    /**
     * Canvas saves players individually, on their owning region thread; when enabled, snapshots are
     * created from its {@code PlayerSaveEvent} instead of from the world save event (see
     * {@link #onWorldSave(WorldSaveEvent)})
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerSave(@NotNull PlayerSaveEvent event) {
        // Quit saves are already handled by the quit listener
        if (event.isQuit() || !getCanvasSettings().isSaveOnPlayerSave()) {
            return;
        }
        final OnlineUser user = BukkitUser.adapt(event.getPlayer(), plugin);
        if (cannotCreateSnapshot(user)) {
            return;
        }
        regionTracker.recordSnapshot(event.getPlayer());
        plugin.runAsync(() -> plugin.getDataSyncer().saveCurrentUserData(
                user, DataSnapshot.SaveCause.PLAYER_SAVE
        ));
    }

    @Override
    @EventHandler(ignoreCancelled = true)
    public void onWorldSave(@NotNull WorldSaveEvent event) {
        // Superseded by per-player PLAYER_SAVE snapshots (region-correct on Canvas)
        if (getCanvasSettings().isSaveOnPlayerSave()) {
            return;
        }
        super.onWorldSave(event);
    }

    @EventHandler
    public void onPlayerRespawnAsync(@NotNull PlayerRespawnAsyncEvent event) {
        // Fired while the player is unloaded from the world; for debug visibility only
        plugin.debug("[Canvas] %s is being respawned (reason: %s, bed: %s, anchor: %s)".formatted(
                event.getPlayer().getName(), event.getRespawnReason(),
                event.isBedSpawn(), event.isAnchorSpawn()
        ));
    }

    /**
     * Fired after the player has been respawned, guaranteed to be in the correct region context;
     * optionally snapshot their post-respawn state so other servers won't apply their dead state
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerPostRespawnAsync(@NotNull PlayerPostRespawnAsyncEvent event) {
        if (!getCanvasSettings().isSaveOnRespawn()) {
            return;
        }
        final OnlineUser user = BukkitUser.adapt(event.getPlayer(), plugin);
        if (cannotCreateSnapshot(user)) {
            return;
        }
        regionTracker.recordRespawn(event.getPlayer());
        plugin.runAsync(() -> plugin.getDataSyncer().saveCurrentUserData(
                user, DataSnapshot.SaveCause.RESPAWN
        ));
    }

    // Stop player-initiated teleports while their data is locked (being synchronized); plugin- and
    // command-initiated teleports (including HuskSync applying synced location data) pass through
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTeleportAsync(@NotNull EntityTeleportAsyncEvent event) {
        if (!(event.getEntity() instanceof Player player) || !isPlayerInitiated(event.getCause())) {
            return;
        }
        if (lockedHandler.cancelPlayerEvent(player.getUniqueId())) {
            event.setCancelled(true);
            plugin.debug("[Canvas] Canceled %s teleport for locked user %s".formatted(
                    event.getType(), player.getName()
            ));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortalAsync(@NotNull EntityPortalAsyncEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (lockedHandler.cancelPlayerEvent(player.getUniqueId())) {
            event.setCancelled(true);
            plugin.debug("[Canvas] Canceled %s portal use for locked user %s".formatted(
                    event.getPortalType(), player.getName()
            ));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityPostTeleportAsync(@NotNull EntityPostTeleportAsyncEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || event.getType() == EntityTeleportAsyncEvent.TeleportType.SAME_REGION) {
            return;
        }
        // Fired once the player has been placed in the destination region
        regionTracker.recordRegionCrossing(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityPostPortalAsync(@NotNull EntityPostPortalAsyncEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        regionTracker.recordRegionCrossing(player);
        plugin.debug("[Canvas] %s completed a %s portal from %s to %s".formatted(
                player.getName(), event.getPortalType(),
                event.getFrom().getName(), event.getTo().getName()
        ));
    }

    // Don't let locked players trigger the end credits (and the dimension change that follows)
    // while their data is still being synchronized
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerViewEndCredits(@NotNull PlayerViewEndCreditsEvent event) {
        if (event.willShowCredits() && lockedHandler.cancelPlayerEvent(event.getPlayer().getUniqueId())) {
            event.setResult(Event.Result.DENY);
        }
    }

    // Canvas restores runtime world unloading (Server#unloadWorldAsync); drop cached map views
    // bound to the unloading world so it can be garbage collected
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnloadAsync(@NotNull WorldUnloadAsyncEvent event) {
        final boolean removed = getPlugin().getMapViews().values()
                .removeIf(view -> event.getWorld().equals(view.getWorld()));
        if (removed) {
            plugin.debug("[Canvas] Dropped cached map views of asynchronously unloading world %s"
                    .formatted(event.getWorld().getName()));
        }
    }

    private boolean cannotCreateSnapshot(@NotNull OnlineUser user) {
        return plugin.isDisabling() || user.isNpc() || user.hasDisconnected() || plugin.isLocked(user.getUuid());
    }

    @NotNull
    private CanvasSettings getCanvasSettings() {
        return plugin.getSettings().getSynchronization().getCanvas();
    }

    private static boolean isPlayerInitiated(@NotNull PlayerTeleportEvent.TeleportCause cause) {
        return switch (cause) {
            case ENDER_PEARL, CONSUMABLE_EFFECT, NETHER_PORTAL, END_PORTAL, END_GATEWAY, SPECTATE -> true;
            default -> false;
        };
    }

}
