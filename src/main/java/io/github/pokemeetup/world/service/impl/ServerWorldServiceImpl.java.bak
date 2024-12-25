package io.github.pokemeetup.world.service.impl;


import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import io.github.pokemeetup.multiplayer.model.WorldObjectUpdate;
import io.github.pokemeetup.player.model.PlayerData;
import io.github.pokemeetup.world.biome.config.BiomeConfigurationLoader;
import io.github.pokemeetup.world.biome.model.Biome;
import io.github.pokemeetup.world.biome.model.BiomeType;
import io.github.pokemeetup.world.config.WorldObjectConfig;
import io.github.pokemeetup.world.model.ChunkData;
import io.github.pokemeetup.world.model.ObjectType;
import io.github.pokemeetup.world.model.WorldData;
import io.github.pokemeetup.world.model.WorldObject;
import io.github.pokemeetup.world.service.TileManager;
import io.github.pokemeetup.world.service.WorldGenerator;
import io.github.pokemeetup.world.service.WorldObjectManager;
import io.github.pokemeetup.world.service.WorldService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@Primary
@Profile("server")
public class ServerWorldServiceImpl extends BaseWorldServiceImpl implements WorldService {
    private static final int TILE_SIZE = 32;
    private static final int CHUNK_SIZE = 16;

    private final WorldGenerator worldGenerator;
    private final WorldObjectManager worldObjectManager;
    private final TileManager tileManager;
    private final BiomeConfigurationLoader biomeLoader;

    private final JsonWorldDataService jsonWorldDataService; // NEW

    private final WorldData worldData = new WorldData();
    private final Map<String, WorldData> loadedWorlds = new ConcurrentHashMap<>();
    private boolean initialized = false;
    @Value("${world.defaultName:defaultWorld}")
    private String defaultWorldName;

    private OrthographicCamera camera = null;

    public ServerWorldServiceImpl(
            WorldGenerator worldGenerator,
            WorldObjectManager worldObjectManager,
            TileManager tileManager,
            BiomeConfigurationLoader biomeLoader,
            JsonWorldDataService jsonWorldDataService
    ) {
        this.worldGenerator = worldGenerator;
        this.worldObjectManager = worldObjectManager;
        this.tileManager = tileManager;
        this.biomeLoader = biomeLoader;
        this.jsonWorldDataService = jsonWorldDataService;
    }

    @Override
    public void initIfNeeded() {
        if (!loadedWorlds.containsKey("serverWorld")) {
            try {
                WorldData wd = new WorldData();
                jsonWorldDataService.loadWorld("serverWorld", wd);
                loadedWorlds.put("serverWorld", wd);
            } catch (IOException e) {
                WorldData newWorld = new WorldData();
                newWorld.setWorldName("serverWorld");
                try {
                    jsonWorldDataService.saveWorld(newWorld);
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
                loadedWorlds.put("serverWorld", newWorld);
            }
        }
    }

    @Override
    public WorldData getWorldData() {
        return worldData;
    }

    @Override
    public TileManager getTileManager() {
        return tileManager;
    }

    // ------------------------------
    // Saving and Loading from JSON
    // ------------------------------
    @Override
    public void loadWorldData() {
        // Instead of querying from DB, load from JSON
        try {
            jsonWorldDataService.loadWorld(defaultWorldName, worldData);
            initIfNeeded();
            log.info("Loaded default world data for '{}' from JSON (server)", defaultWorldName);
        } catch (IOException e) {
            log.warn("Failed to load default world '{}': {}", defaultWorldName, e.getMessage());
        }
    }

    @Override
    public boolean createWorld(String worldName, long seed) {
        // We simply set in-memory and call save
        if (jsonWorldDataService.worldExists(worldName)) {
            log.warn("World '{}' already exists, cannot create", worldName);
            return false;
        }

        long now = System.currentTimeMillis();
        worldData.setWorldName(worldName);
        worldData.setSeed(seed);
        worldData.setCreatedDate(now);
        worldData.setLastPlayed(now);
        worldData.setPlayedTime(0);

        // Immediately save to JSON
        try {
            jsonWorldDataService.saveWorld(worldData);
        } catch (IOException e) {
            log.error("Failed to create new world '{}': {}", worldName, e.getMessage());
            return false;
        }
        log.info("Created new world '{}' with seed {} in JSON (server)", worldName, seed);
        return true;
    }

    @Override
    public void saveWorldData() {
        WorldData wd = loadedWorlds.get("serverWorld");
        if (wd != null) {
            try {
                jsonWorldDataService.saveWorld(wd);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void loadWorld(String worldName) {
        try {
            jsonWorldDataService.loadWorld(worldName, worldData);
            initIfNeeded();
            log.info("Loaded world data for '{}' from JSON (server)", worldName);
        } catch (IOException e) {
            log.warn("World '{}' does not exist in JSON or failed to load: {}", worldName, e.getMessage());
        }
    }

    // Return chunk tiles from loaded chunk or generate if missing
    @Override
    public int[][] getChunkTiles(int chunkX, int chunkY) {
        WorldData wd = loadedWorlds.get("serverWorld");
        if (wd == null) return null;

        String key = chunkX + "," + chunkY;
        if (!wd.getChunks().containsKey(key)) {
            try {
                var chunkData = jsonWorldDataService.loadChunk("serverWorld", chunkX, chunkY);
                if (chunkData != null) {
                    wd.getChunks().put(key, chunkData);
                    return chunkData.getTiles();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }
        return wd.getChunks().get(key).getTiles();
    }

    private void loadOrGenerateChunk(int chunkX, int chunkY) {
        String key = chunkX + "," + chunkY;
        // 1) Attempt to load from JSON
        try {
            ChunkData loaded = jsonWorldDataService.loadChunk(worldData.getWorldName(), chunkX, chunkY);
            if (loaded != null) {
                worldObjectManager.loadObjectsForChunk(chunkX, chunkY, loaded.getObjects());
                worldData.getChunks().put(key, loaded);
                return;
            }
        } catch (IOException e) {
            log.warn("Failed reading chunk from JSON: {}", e.getMessage());
        }

        // 2) Not found or no chunk file => generate
        int[][] tiles = worldGenerator.generateChunk(chunkX, chunkY);
        ChunkData cData = new ChunkData();
        cData.setChunkX(chunkX);
        cData.setChunkY(chunkY);

        cData.setTiles(tiles);

        Biome biome = worldGenerator.getBiomeForChunk(chunkX, chunkY);
        List<WorldObject> objs =
                worldObjectManager.generateObjectsForChunk(chunkX, chunkY, tiles, biome, worldData.getSeed());
        cData.setObjects(objs);

        worldData.getChunks().put(key, cData);

        // 3) Save newly generated chunk to JSON
        try {
            jsonWorldDataService.saveChunk(worldData.getWorldName(), cData);
        } catch (IOException e) {
            log.error("Failed to save newly generated chunk: {}", e.getMessage());
        }
    }

    @Override
    public boolean isChunkLoaded(Vector2 chunkPos) {
        String key = String.format("%d,%d", (int) chunkPos.x, (int) chunkPos.y);
        return worldData.getChunks().containsKey(key);
    }

    @Override
    public void loadChunk(Vector2 chunkPos) {
        loadOrGenerateChunk((int) chunkPos.x, (int) chunkPos.y);
    }

    @Override
    public List<WorldObject> getVisibleObjects(Rectangle viewBounds) {
        // Implementation unchanged, except no DB call
        List<WorldObject> visibleObjects = new ArrayList<>();
        Map<String, ChunkData> visibleChunks = getVisibleChunks(viewBounds);
        for (ChunkData chunk : visibleChunks.values()) {
            if (chunk.getObjects() != null) {
                visibleObjects.addAll(chunk.getObjects());
            }
        }
        return visibleObjects;
    }

    @Override
    public Map<String, ChunkData> getVisibleChunks(Rectangle viewBounds) {
        // Implementation is basically the same
        Map<String, ChunkData> visibleChunks = new HashMap<>();

        int startChunkX = (int) Math.floor(viewBounds.x / (CHUNK_SIZE * TILE_SIZE));
        int startChunkY = (int) Math.floor(viewBounds.y / (CHUNK_SIZE * TILE_SIZE));
        int endChunkX = (int) Math.ceil((viewBounds.x + viewBounds.width) / (CHUNK_SIZE * TILE_SIZE));
        int endChunkY = (int) Math.ceil((viewBounds.y + viewBounds.height) / (CHUNK_SIZE * TILE_SIZE));

        for (int x = startChunkX; x <= endChunkX; x++) {
            for (int y = startChunkY; y <= endChunkY; y++) {
                String key = x + "," + y;
                if (!worldData.getChunks().containsKey(key)) {
                    loadOrGenerateChunk(x, y);
                }
                ChunkData chunk = worldData.getChunks().get(key);
                if (chunk != null) {
                    visibleChunks.put(key, chunk);
                }
            }
        }

        return visibleChunks;
    }

    @Override
    public void setPlayerData(PlayerData pd) {
        WorldData wd = loadedWorlds.get("serverWorld");
        if (wd == null) return;
        wd.getPlayers().put(pd.getUsername(), pd);
        try {
            jsonWorldDataService.savePlayerData("serverWorld", pd);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }



    @Override
    public PlayerData getPlayerData(String username) {
        WorldData wd = loadedWorlds.get("serverWorld");
        if (wd == null) return null;
        PlayerData existing = wd.getPlayers().get(username);
        if (existing != null) return existing;
        try {
            PlayerData pd = jsonWorldDataService.loadPlayerData("serverWorld", username);
            if (pd != null) {
                wd.getPlayers().put(username, pd);
            }
            return pd;
        } catch (IOException e) {
            return null;
        }
    }


    @Override
    public List<String> getAvailableWorlds() {
        return jsonWorldDataService.listAllWorlds();
    }

    @Override
    public void deleteWorld(String worldName) {
        if (!jsonWorldDataService.worldExists(worldName)) {
            log.warn("World '{}' does not exist in JSON, cannot delete (server)", worldName);
            return;
        }

        jsonWorldDataService.deleteWorld(worldName);

        // If it’s the currently loaded world, clear it
        if (worldData.getWorldName() != null && worldData.getWorldName().equals(worldName)) {
            worldData.setWorldName(null);
            worldData.setSeed(0);
            worldData.getPlayers().clear();
            worldData.getChunks().clear();
            worldData.setCreatedDate(0);
            worldData.setLastPlayed(0);
            worldData.setPlayedTime(0);
            log.info("Cleared current loaded world data because it was deleted (server).");
        }
        log.info("Deleted world '{}' from JSON (server)", worldName);
    }

    @Override
    public void regenerateChunk(int chunkX, int chunkY) {
        String key = chunkX + "," + chunkY;
        worldData.getChunks().remove(key);
        // Also delete chunk JSON if present
        jsonWorldDataService.deleteChunk(worldData.getWorldName(), chunkX, chunkY);
        loadOrGenerateChunk(chunkX, chunkY);
    }

    @Override
    public void generateWorldThumbnail(String worldName) {
        log.info("Skipping world thumbnail generation on server.");
    }


    @Override
    public void loadOrReplaceChunkData(int chunkX, int chunkY, int[][] tiles,
                                       java.util.List<WorldObject> objects) {
        WorldData wd = loadedWorlds.get("serverWorld");
        if (wd == null) return;
        String key = chunkX + "," + chunkY;
        var chunk = wd.getChunks().get(key);
        if (chunk == null) {
            chunk = new io.github.pokemeetup.world.model.ChunkData();
            chunk.setChunkX(chunkX);
            chunk.setChunkY(chunkY);
            wd.getChunks().put(key, chunk);
        }
        chunk.setTiles(tiles);
        chunk.setObjects(objects);
        try {
            jsonWorldDataService.saveChunk("serverWorld", chunk);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    @Override
    public void updateWorldObjectState(WorldObjectUpdate update) {
        // Example: move or remove an object in the chunk
        WorldData wd = loadedWorlds.get("serverWorld");
        if (wd == null) return;
        String chunkKey = (update.getTileX() / 16) + "," + (update.getTileY() / 16);

        var chunkData = wd.getChunks().get(chunkKey);
        if (chunkData != null) {
            if (update.isRemoved()) {
                chunkData.getObjects().removeIf(o -> o.getId().equals(update.getObjectId()));
            } else {
                // Possibly find or create
                var existing = chunkData.getObjects().stream()
                        .filter(o -> o.getId().equals(update.getObjectId()))
                        .findFirst();
                if (existing.isPresent()) {
                    // update position
                    var wo = existing.get();
                    wo.setTileX(update.getTileX());
                    wo.setTileY(update.getTileY());
                } else {
                    // create new
                    WorldObject obj = new WorldObject(
                            update.getTileX(),
                            update.getTileY(),
                            // parse from update.getType()
                            ObjectType.valueOf(update.getType()),
                            true // or read from config
                    );
                    obj.setId(update.getObjectId());
                    chunkData.getObjects().add(obj);
                }
            }
        }
        // Optionally save chunk to disk
        try {
            jsonWorldDataService.saveChunk("serverWorld", chunkData);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    @Override
    public OrthographicCamera getCamera() {
        return camera;
    }

    @Override
    public void setCamera(OrthographicCamera camera) {
        this.camera = camera;
    }
}
