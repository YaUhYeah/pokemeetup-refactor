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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
    private final JsonWorldDataService jsonWorldDataService;

    private final WorldData worldData = new WorldData();
    private final Map<String, WorldData> loadedWorlds = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock worldLock = new ReentrantReadWriteLock();
    private final Map<String, ReentrantReadWriteLock> chunkLocks = new ConcurrentHashMap<>();

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
        if (initialized) {
            return;
        }

        worldLock.writeLock().lock();
        try {
            if (initialized) {
                return;
            }

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
                        log.error("Failed to save new server world: {}", ex.getMessage());
                    }
                    loadedWorlds.put("serverWorld", newWorld);
                }
            }

            initialized = true;
            log.info("Server world service initialized");
        } finally {
            worldLock.writeLock().unlock();
        }
    }

    @Override
    public WorldData getWorldData() {
        worldLock.readLock().lock();
        try {
            return worldData;
        } finally {
            worldLock.readLock().unlock();
        }
    }

    @Override
    public TileManager getTileManager() {
        return tileManager;
    }

    private ReentrantReadWriteLock getChunkLock(String key) {
        return chunkLocks.computeIfAbsent(key, k -> new ReentrantReadWriteLock());
    }

    private void loadOrGenerateChunk(int chunkX, int chunkY) {
        String key = chunkX + "," + chunkY;
        
        // Check if chunk is already loaded
        worldLock.readLock().lock();
        try {
            if (worldData.getChunks().containsKey(key)) {
                log.debug("Chunk {} already loaded", key);
                return;
            }
        } finally {
            worldLock.readLock().unlock();
        }

        // Get or create chunk lock
        ReentrantReadWriteLock chunkLock = getChunkLock(key);
        chunkLock.writeLock().lock();
        try {
            // Double-check after acquiring lock
            if (worldData.getChunks().containsKey(key)) {
                log.debug("Chunk {} already loaded (after lock)", key);
                return;
            }

            // 1) Attempt to load from JSON
            try {
                ChunkData loaded = jsonWorldDataService.loadChunk(worldData.getWorldName(), chunkX, chunkY);
                if (loaded != null && validateChunkData(loaded)) {
                    worldObjectManager.loadObjectsForChunk(chunkX, chunkY, loaded.getObjects());
                    worldData.getChunks().put(key, loaded);
                    log.debug("Successfully loaded chunk {} from JSON", key);
                    return;
                }
            } catch (IOException e) {
                log.warn("Failed reading chunk {} from JSON: {}", key, e.getMessage());
            }

            // 2) Generate new chunk
            try {
                int[][] tiles = worldGenerator.generateChunk(chunkX, chunkY);
                if (!validateTiles(tiles)) {
                    throw new IllegalStateException("Generated invalid tiles for chunk " + key);
                }

                ChunkData cData = new ChunkData();
                cData.setChunkX(chunkX);
                cData.setChunkY(chunkY);
                cData.setTiles(tiles);

                Biome biome = worldGenerator.getBiomeForChunk(chunkX, chunkY);
                List<WorldObject> objs = worldObjectManager.generateObjectsForChunk(
                    chunkX, chunkY, tiles, biome, worldData.getSeed());
                
                if (!validateWorldObjects(objs, tiles)) {
                    throw new IllegalStateException("Generated invalid objects for chunk " + key);
                }
                
                cData.setObjects(objs);
                worldData.getChunks().put(key, cData);

                // 3) Save newly generated chunk to JSON
                try {
                    jsonWorldDataService.saveChunk(worldData.getWorldName(), cData);
                    log.debug("Successfully generated and saved chunk {}", key);
                } catch (IOException e) {
                    log.error("Failed to save newly generated chunk {}: {}", key, e.getMessage());
                }
            } catch (Exception e) {
                log.error("Failed to generate chunk {}: {}", key, e.getMessage());
                throw new RuntimeException("Failed to generate chunk " + key, e);
            }
        } finally {
            chunkLock.writeLock().unlock();
        }
    }

    private boolean validateChunkData(ChunkData chunk) {
        if (chunk == null) return false;
        
        // Validate basic properties
        if (chunk.getChunkX() == 0 && chunk.getChunkY() == 0 && chunk.getTiles() == null) {
            log.warn("Invalid chunk data: empty chunk");
            return false;
        }

        // Validate tiles
        if (!validateTiles(chunk.getTiles())) {
            return false;
        }

        // Validate objects if present
        if (chunk.getObjects() != null && !validateWorldObjects(chunk.getObjects(), chunk.getTiles())) {
            return false;
        }

        return true;
    }

    private boolean validateTiles(int[][] tiles) {
        if (tiles == null || tiles.length != CHUNK_SIZE) {
            log.warn("Invalid tiles: null or wrong dimensions");
            return false;
        }

        for (int[] row : tiles) {
            if (row == null || row.length != CHUNK_SIZE) {
                log.warn("Invalid tiles: null row or wrong row length");
                return false;
            }
            for (int tile : row) {
                if (tile < 0) {
                    log.warn("Invalid tiles: negative tile ID");
                    return false;
                }
            }
        }
        return true;
    }

    private boolean validateWorldObjects(List<WorldObject> objects, int[][] tiles) {
        if (objects == null) return true; // Empty object list is valid

        for (WorldObject obj : objects) {
            // Check object position is within chunk bounds
            if (obj.getTileX() < 0 || obj.getTileX() >= CHUNK_SIZE || 
                obj.getTileY() < 0 || obj.getTileY() >= CHUNK_SIZE) {
                log.warn("Invalid object position: {} at ({}, {})", 
                    obj.getId(), obj.getTileX(), obj.getTileY());
                return false;
            }

            // Check object type is valid
            if (obj.getType() == null) {
                log.warn("Invalid object type for object {}", obj.getId());
                return false;
            }

            // Check object doesn't overlap with impassable tiles
            int tileType = tiles[obj.getTileX()][obj.getTileY()];
            if (tileManager.isTileImpassable(tileType) && obj.isCollidable()) {
                log.warn("Object {} placed on impassable tile at ({}, {})", 
                    obj.getId(), obj.getTileX(), obj.getTileY());
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isChunkLoaded(Vector2 chunkPos) {
        String key = String.format("%d,%d", (int) chunkPos.x, (int) chunkPos.y);
        worldLock.readLock().lock();
        try {
            return worldData.getChunks().containsKey(key);
        } finally {
            worldLock.readLock().unlock();
        }
    }

    @Override
    public void loadChunk(Vector2 chunkPos) {
        loadOrGenerateChunk((int) chunkPos.x, (int) chunkPos.y);
    }

    @Override
    public Map<String, ChunkData> getVisibleChunks(Rectangle viewBounds) {
        Map<String, ChunkData> visibleChunks = new HashMap<>();

        int startChunkX = (int) Math.floor(viewBounds.x / (CHUNK_SIZE * TILE_SIZE));
        int startChunkY = (int) Math.floor(viewBounds.y / (CHUNK_SIZE * TILE_SIZE));
        int endChunkX = (int) Math.ceil((viewBounds.x + viewBounds.width) / (CHUNK_SIZE * TILE_SIZE));
        int endChunkY = (int) Math.ceil((viewBounds.y + viewBounds.height) / (CHUNK_SIZE * TILE_SIZE));

        // First, collect all chunk coordinates we need
        Set<String> requiredChunks = new HashSet<>();
        for (int x = startChunkX; x <= endChunkX; x++) {
            for (int y = startChunkY; y <= endChunkY; y++) {
                requiredChunks.add(x + "," + y);
            }
        }

        // Load all missing chunks first
        for (String key : requiredChunks) {
            String[] coords = key.split(",");
            int x = Integer.parseInt(coords[0]);
            int y = Integer.parseInt(coords[1]);
            
            if (!worldData.getChunks().containsKey(key)) {
                loadOrGenerateChunk(x, y);
            }
        }

        // Now collect all loaded chunks
        worldLock.readLock().lock();
        try {
            for (String key : requiredChunks) {
                ChunkData chunk = worldData.getChunks().get(key);
                if (chunk != null) {
                    visibleChunks.put(key, chunk);
                }
            }
        } finally {
            worldLock.readLock().unlock();
        }

        return visibleChunks;
    }

    @Override
    public List<WorldObject> getVisibleObjects(Rectangle viewBounds) {
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
    public void loadWorldData() {
        worldLock.writeLock().lock();
        try {
            try {
                jsonWorldDataService.loadWorld(defaultWorldName, worldData);
                initIfNeeded();
                log.info("Loaded default world data for '{}' from JSON (server)", defaultWorldName);
            } catch (IOException e) {
                log.warn("Failed to load default world '{}': {}", defaultWorldName, e.getMessage());
            }
        } finally {
            worldLock.writeLock().unlock();
        }
    }

    @Override
    public void saveWorldData() {
        worldLock.readLock().lock();
        try {
            WorldData wd = loadedWorlds.get("serverWorld");
            if (wd != null) {
                try {
                    jsonWorldDataService.saveWorld(wd);
                } catch (IOException e) {
                    log.error("Failed to save world data: {}", e.getMessage());
                }
            }
        } finally {
            worldLock.readLock().unlock();
        }
    }

    @Override
    public void loadWorld(String worldName) {
        worldLock.writeLock().lock();
        try {
            try {
                jsonWorldDataService.loadWorld(worldName, worldData);
                initIfNeeded();
                log.info("Loaded world data for '{}' from JSON (server)", worldName);
            } catch (IOException e) {
                log.warn("World '{}' does not exist in JSON or failed to load: {}", worldName, e.getMessage());
            }
        } finally {
            worldLock.writeLock().unlock();
        }
    }

    @Override
    public boolean createWorld(String worldName, long seed) {
        if (jsonWorldDataService.worldExists(worldName)) {
            log.warn("World '{}' already exists, cannot create", worldName);
            return false;
        }

        worldLock.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            worldData.setWorldName(worldName);
            worldData.setSeed(seed);
            worldData.setCreatedDate(now);
            worldData.setLastPlayed(now);
            worldData.setPlayedTime(0);

            try {
                jsonWorldDataService.saveWorld(worldData);
            } catch (IOException e) {
                log.error("Failed to create new world '{}': {}", worldName, e.getMessage());
                return false;
            }
            log.info("Created new world '{}' with seed {} in JSON (server)", worldName, seed);
            return true;
        } finally {
            worldLock.writeLock().unlock();
        }
    }

    @Override
    public void deleteWorld(String worldName) {
        if (!jsonWorldDataService.worldExists(worldName)) {
            log.warn("World '{}' does not exist in JSON, cannot delete (server)", worldName);
            return;
        }

        worldLock.writeLock().lock();
        try {
            jsonWorldDataService.deleteWorld(worldName);

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
        } finally {
            worldLock.writeLock().unlock();
        }
    }

    @Override
    public void regenerateChunk(int chunkX, int chunkY) {
        String key = chunkX + "," + chunkY;
        ReentrantReadWriteLock chunkLock = getChunkLock(key);
        
        chunkLock.writeLock().lock();
        try {
            worldData.getChunks().remove(key);
            jsonWorldDataService.deleteChunk(worldData.getWorldName(), chunkX, chunkY);
            loadOrGenerateChunk(chunkX, chunkY);
        } finally {
            chunkLock.writeLock().unlock();
        }
    }

    @Override
    public void loadOrReplaceChunkData(int chunkX, int chunkY, int[][] tiles, List<WorldObject> objects) {
        String key = chunkX + "," + chunkY;
        ReentrantReadWriteLock chunkLock = getChunkLock(key);
        
        chunkLock.writeLock().lock();
        try {
            WorldData wd = loadedWorlds.get("serverWorld");
            if (wd == null) return;

            ChunkData chunk = wd.getChunks().get(key);
            if (chunk == null) {
                chunk = new ChunkData();
                chunk.setChunkX(chunkX);
                chunk.setChunkY(chunkY);
                wd.getChunks().put(key, chunk);
            }

            if (!validateTiles(tiles)) {
                log.error("Invalid tiles provided for chunk {}", key);
                return;
            }

            if (!validateWorldObjects(objects, tiles)) {
                log.error("Invalid objects provided for chunk {}", key);
                return;
            }

            chunk.setTiles(tiles);
            chunk.setObjects(objects);

            try {
                jsonWorldDataService.saveChunk("serverWorld", chunk);
            } catch (IOException e) {
                log.error("Failed to save chunk {}: {}", key, e.getMessage());
            }
        } finally {
            chunkLock.writeLock().unlock();
        }
    }

    @Override
    public void updateWorldObjectState(WorldObjectUpdate update) {
        String chunkKey = (update.getTileX() / CHUNK_SIZE) + "," + (update.getTileY() / CHUNK_SIZE);
        ReentrantReadWriteLock chunkLock = getChunkLock(chunkKey);
        
        chunkLock.writeLock().lock();
        try {
            WorldData wd = loadedWorlds.get("serverWorld");
            if (wd == null) return;

            ChunkData chunkData = wd.getChunks().get(chunkKey);
            if (chunkData == null) {
                log.warn("Attempted to update object in non-existent chunk {}", chunkKey);
                return;
            }

            if (update.isRemoved()) {
                chunkData.getObjects().removeIf(o -> o.getId().equals(update.getObjectId()));
            } else {
                var existing = chunkData.getObjects().stream()
                        .filter(o -> o.getId().equals(update.getObjectId()))
                        .findFirst();

                if (existing.isPresent()) {
                    var wo = existing.get();
                    wo.setTileX(update.getTileX());
                    wo.setTileY(update.getTileY());
                } else {
                    WorldObject obj = new WorldObject(
                            update.getTileX(),
                            update.getTileY(),
                            ObjectType.valueOf(update.getType()),
                            true
                    );
                    obj.setId(update.getObjectId());
                    chunkData.getObjects().add(obj);
                }
            }

            try {
                jsonWorldDataService.saveChunk("serverWorld", chunkData);
            } catch (IOException e) {
                log.error("Failed to save chunk after object update: {}", e.getMessage());
            }
        } finally {
            chunkLock.writeLock().unlock();
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