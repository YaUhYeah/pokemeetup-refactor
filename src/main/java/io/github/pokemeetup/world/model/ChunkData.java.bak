package io.github.pokemeetup.world.model;

import lombok.Data;

import java.io.*;
import java.util.List;

@Data
public class ChunkData {
    private int chunkX;
    private int chunkY;

    private int[][] tiles;

    private List<WorldObject> objects;

    private transient byte[] tilesBlob;

    public int[][] getTiles() {
        if (tiles == null && tilesBlob != null) {
            tiles = deserializeTiles(tilesBlob);
        }
        return tiles;
    }

    public void setTiles(int[][] tiles) {
        this.tiles = tiles;
        this.tilesBlob = serializeTiles(tiles);
    }

    private byte[] serializeTiles(int[][] tiles) {
        if (tiles == null) return null;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(tiles);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize tiles", e);
        }
    }

    private int[][] deserializeTiles(byte[] data) {
        if (data == null) return null;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (int[][]) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize tiles", e);
        }
    }
}
