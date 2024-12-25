package io.github.pokemeetup.world.service.impl;

import com.badlogic.gdx.math.MathUtils;
import io.github.pokemeetup.utils.OpenSimplex2;
import io.github.pokemeetup.world.biome.model.BiomeType;
import io.github.pokemeetup.world.config.WorldConfig;
import io.github.pokemeetup.world.biome.model.Biome;
import io.github.pokemeetup.world.service.WorldGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Random;

@Service
public class WorldGeneratorImpl implements WorldGenerator {
    private long seed;
    private Map<BiomeType, Biome> biomes;
    private final WorldConfig config;

    @Autowired
    public WorldGeneratorImpl(WorldConfig config) {
        this.config = config;
    }

    @Override
    public void setSeedAndBiomes(long seed, Map<BiomeType, Biome> biomes) {
        this.seed = seed;
        this.biomes = biomes;
    }

    @Override
    public Biome getBiomeForChunk(int chunkX, int chunkY) {
        if (biomes == null || biomes.isEmpty()) {
            return null;
        }

        float NOISE_SCALE = 0.005f;
        float n = OpenSimplex2.noise2(seed, chunkX * NOISE_SCALE, chunkY * NOISE_SCALE);
        BiomeType selectedBiome = n > 0 ? BiomeType.PLAINS : BiomeType.DESERT;
        return biomes.get(selectedBiome);
    }

    @Override
    public int[][] generateChunk(int chunkX, int chunkY) {
        // Optionally get the biome for the chunk
        Biome biome = getBiomeForChunk(chunkX, chunkY);
        int chunkSize = config.getChunkSize();

        int[][] tiles = new int[chunkSize][chunkSize];
        if (biome == null) {
            // Fallback if no biome found
            for (int x = 0; x < chunkSize; x++) {
                for (int y = 0; y < chunkSize; y++) {
                    tiles[x][y] = 1;
                }
            }
            return tiles;
        }

        Random chunkRandom = new Random(seed ^ (chunkX * 341_757L) ^ (chunkY * 132_721L));
        double total = biome.getTileDistribution().values().stream().mapToDouble(Double::doubleValue).sum();

        for (int x = 0; x < chunkSize; x++) {
            for (int y = 0; y < chunkSize; y++) {
                double roll = chunkRandom.nextDouble() * total;
                double cumulative = 0;
                for (Map.Entry<Integer, Double> entry : biome.getTileDistribution().entrySet()) {
                    cumulative += entry.getValue();
                    if (roll < cumulative) {
                        tiles[x][y] = entry.getKey();
                        break;
                    }
                }
            }
        }
        return tiles;
    }

}
