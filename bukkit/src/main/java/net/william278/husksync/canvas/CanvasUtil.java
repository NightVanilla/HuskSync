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

/**
 * Utility for detecting whether the plugin is running on a Canvas (<a href="https://canvasmc.io">canvasmc.io</a>)
 * server, which exposes additional region threading APIs on top of the Paper/Folia API.
 * <p>
 * This class deliberately contains no references to Canvas API types, so it is safe to load
 * on any server implementation. Classes that do use the Canvas API ({@code CanvasEventListener},
 * {@code CanvasRegionSyncTracker}) must only be loaded after {@link #isCanvasServer()} returns {@code true}.
 */
public final class CanvasUtil {

    // A Canvas API class that has been present since Canvas adopted region threading
    private static final String CANVAS_CLASS = "io.canvasmc.canvas.region.RegionTickData";

    private static Boolean canvas;

    private CanvasUtil() {
    }

    /**
     * Returns whether the current server is running Canvas
     *
     * @return {@code true} if the Canvas API is available on the server
     */
    public static boolean isCanvasServer() {
        if (canvas == null) {
            try {
                Class.forName(CANVAS_CLASS);
                canvas = true;
            } catch (ClassNotFoundException e) {
                canvas = false;
            }
        }
        return canvas;
    }

}
