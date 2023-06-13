/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.cache;

import baritone.Baritone;
import baritone.api.cache.IWorldProvider;
import baritone.api.utils.Helper;
import baritone.bot.BotPlayerContext;
import baritone.utils.accessor.IAnvilChunkLoader;
import baritone.utils.accessor.IChunkProviderServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.apache.commons.lang3.SystemUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * @author Brady
 * @since 8/4/2018
 */
public class WorldProvider implements IWorldProvider, Helper {

    private static final Map<Path, WorldData> worldCache = new HashMap<>(); // this is how the bots have the same cached world

    private Baritone baritone;
    private WorldData currentWorld;
    private World mcWorld; // this let's us detect a broken load/unload hook

    public WorldProvider(Baritone baritone) {
        this.baritone = baritone;
    }

    @Override
    public final WorldData getCurrentWorld() {
        detectAndHandleBrokenLoading();
        return this.currentWorld;
    }

    /**
     * Called when a new world is initialized to discover the
     *
     * @param dimension The ID of the world's dimension
     */
    public final void initWorld(int dimension) {
        File directory;
        File readme;

        IntegratedServer integratedServer = mc.getIntegratedServer();

        // If there is an integrated server running (Aka Singleplayer) then do magic to find the world save file
        if (mc.isSingleplayer()) {
            WorldServer localServerWorld = integratedServer.getWorld(dimension);
            IChunkProviderServer provider = (IChunkProviderServer) localServerWorld.getChunkProvider();
            IAnvilChunkLoader loader = (IAnvilChunkLoader) provider.getChunkLoader();
            directory = loader.getChunkSaveLocation();

            // Gets the "depth" of this directory relative the the game's run directory, 2 is the location of the world
            if (directory.toPath().relativize(mc.gameDir.toPath()).getNameCount() != 2) {
                // subdirectory of the main save directory for this world
                directory = directory.getParentFile();
            }

            directory = new File(directory, "baritone");
            readme = directory;
        } else { // Otherwise, the server must be remote...
            String folderName;
            if (mc.getCurrentServerData() != null) {
                folderName = mc.getCurrentServerData().serverIP;
            } else {
                //replaymod causes null currentServerData and false singleplayer.
                System.out.println("World seems to be a replay. Not loading Baritone cache.");
                currentWorld = null;
                mcWorld = mc.world;
                return;
            }
            if (SystemUtils.IS_OS_WINDOWS) {
                folderName = folderName.replace(":", "_");
            }
            directory = new File(Baritone.getDir(), folderName);
            readme = Baritone.getDir();
        }

        // lol wtf is this baritone folder in my minecraft save?
        try (FileOutputStream out = new FileOutputStream(new File(readme, "readme.txt"))) {
            // good thing we have a readme
            out.write("https://github.com/cabaletta/baritone\n".getBytes());
        } catch (IOException ignored) {}

        if (this.baritone.getPlayerContext() instanceof BotPlayerContext) {
            directory = new File(directory, "bot");
        }

        // We will actually store the world data in a subfolder: "DIM<id>"
        Path dir = new File(directory, "DIM" + dimension).toPath();
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException ignored) {}
        }

        System.out.println("Baritone world data dir: " + dir);
        synchronized (worldCache) {
            this.currentWorld = worldCache.computeIfAbsent(dir, d -> new WorldData(d, dimension));
        }
        this.mcWorld = mc.world;
    }

    public final void closeWorld() {
        WorldData world = this.currentWorld;
        this.currentWorld = null;
        this.mcWorld = null;
        if (world == null) {
            return;
        }
        world.onClose();
    }

    public final void ifWorldLoaded(Consumer<WorldData> currentWorldConsumer) {
        detectAndHandleBrokenLoading();
        if (this.currentWorld != null) {
            currentWorldConsumer.accept(this.currentWorld);
        }
    }

    private final void detectAndHandleBrokenLoading() {
        if (this.mcWorld != mc.world) {
            if (this.currentWorld != null) {
                System.out.println("mc.world unloaded unnoticed! Unloading Baritone cache now.");
                closeWorld();
            }
            if (mc.world != null) {
                System.out.println("mc.world loaded unnoticed! Loading Baritone cache now.");
                initWorld(mc.world.provider.getDimensionType().getId());
            }
        } else if (currentWorld == null && mc.world != null && (mc.isSingleplayer() || mc.getCurrentServerData() != null)) {
            System.out.println("Retrying to load Baritone cache");
            initWorld(mc.world.provider.getDimensionType().getId());
        }
    }
}
