package io.github.pokemeetup.world.model;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

@Data
@Slf4j
public class ChunkData {
    private static final int CHUNK_SIZE = 16;
    
    private int chunkX;
    private int chunkY;
    private int[][] tiles;
    private List<WorldObject> objects;
    private transient byte[] tilesBlob;
    private transient AtomicBoolean isDirty = new AtomicBoolean(false);

    public ChunkData() {
        this.objects = new ArrayList<>();
    }

    public synchronized int[][] getTiles() {
        if (tiles == null && tilesBlob != null) {
            tiles = deserializeTiles(tilesBlob);
            if (tiles == null) {
                // If deserialization fails, create empty tiles
                tiles = new int[CHUNK_SIZE][CHUNK_SIZE];
                isDirty.set(true);
                log.warn("Failed to deserialize tiles for chunk ({},{}), created empty tiles", chunkX, chunkY);
            }
        }
        return tiles;
    }

    public synchronized void setTiles(int[][] newTiles) {
        if (newTiles == null || newTiles.length != CHUNK_SIZE || newTiles[0].length != CHUNK_SIZE) {
            throw new IllegalArgumentException("Invalid tiles array dimensions");
        }
        
        // Deep copy the tiles to prevent external modifications
        this.tiles = new int[CHUNK_SIZE][CHUNK_SIZE];
        for (int i = 0; i < CHUNK_SIZE; i++) {
            System.arraycopy(newTiles[i], 0, this.tiles[i], 0, CHUNK_SIZE);
        }
        
        // Update blob and mark as dirty
        this.tilesBlob = serializeTiles(this.tiles);
        isDirty.set(true);
    }

    public synchronized List<WorldObject> getObjects() {
        if (objects == null) {
            objects = new ArrayList<>();
            isDirty.set(true);
        }
        return objects;
    }

    public synchronized void setObjects(List<WorldObject> newObjects) {
        // Create a defensive copy of the objects list
        this.objects = new ArrayList<>(newObjects);
        isDirty.set(true);
    }

    public boolean isDirty() {
        return isDirty.get();
    }

    public void clearDirty() {
        isDirty.set(false);
    }

    private byte[] serializeTiles(int[][] tiles) {
        if (tiles == null) return null;
        
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DeflaterOutputStream dos = new DeflaterOutputStream(baos);
             ObjectOutputStream oos = new ObjectOutputStream(dos)) {
            
            oos.writeObject(tiles);
            oos.flush();
            dos.finish();
            return baos.toByteArray();
            
        } catch (IOException e) {
            log.error("Failed to serialize tiles for chunk ({},{}): {}", chunkX, chunkY, e.getMessage());
            return null;
        }
    }

    private int[][] deserializeTiles(byte[] data) {
        if (data == null) return null;
        
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             InflaterInputStream iis = new InflaterInputStream(bais);
             ObjectInputStream ois = new ObjectInputStream(iis)) {
            
            Object obj = ois.readObject();
            if (obj instanceof int[][] tiles) {
                // Validate dimensions
                if (tiles.length != CHUNK_SIZE || tiles[0].length != CHUNK_SIZE) {
                    log.error("Invalid tile dimensions in chunk ({},{})", chunkX, chunkY);
                    return null;
                }
                return tiles;
            }
            
            log.error("Invalid tile data type in chunk ({},{})", chunkX, chunkY);
            return null;
            
        } catch (IOException | ClassNotFoundException e) {
            log.error("Failed to deserialize tiles for chunk ({},{}): {}", chunkX, chunkY, e.getMessage());
            return null;
        }
    }

    public void validate() throws IllegalStateException {
        // Validate tiles
        if (getTiles() == null) {
            throw new IllegalStateException("Chunk tiles cannot be null");
        }

        if (tiles.length != CHUNK_SIZE || tiles[0].length != CHUNK_SIZE) {
            throw new IllegalStateException(
                String.format("Invalid chunk dimensions: %dx%d", tiles.length, tiles[0].length));
        }

        // Validate objects
        if (objects != null) {
            for (WorldObject obj : objects) {
                if (obj.getTileX() < 0 || obj.getTileX() >= CHUNK_SIZE || 
                    obj.getTileY() < 0 || obj.getTileY() >= CHUNK_SIZE) {
                    throw new IllegalStateException(
                        String.format("Object %s position (%d,%d) outside chunk bounds", 
                            obj.getId(), obj.getTileX(), obj.getTileY()));
                }
            }
        }
    }
}