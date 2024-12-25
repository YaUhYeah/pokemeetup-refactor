package io.github.pokemeetup.world.service;

import com.badlogic.gdx.math.Vector2;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

@Service
@Slf4j
public class ChunkLoaderService {
    private static final int VISIBLE_RADIUS = 2;
    private static final int PRELOAD_RADIUS = 4;
    private static final int CHUNK_LOAD_TIMEOUT_MS = 5000;

    private final Map<Vector2, Float> chunkFadeStates = new ConcurrentHashMap<>();
    private final Set<Vector2> preloadedChunks = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final WorldService worldService;
    private final ExecutorService executorService;
    private final Map<Vector2, CompletableFuture<Void>> loadingChunks = new ConcurrentHashMap<>();
    private final Map<Vector2, Long> chunkLoadAttempts = new ConcurrentHashMap<>();
    
    public ChunkLoaderService(WorldService worldService) {
        this.worldService = worldService;
        this.executorService = Executors.newFixedThreadPool(4);
    }

    public void updatePlayerPosition(float playerX, float playerY) {
        int chunkX = (int) Math.floor(playerX / (16 * 32));
        int chunkY = (int) Math.floor(playerY / (16 * 32));


        Set<Vector2> requiredChunks = new HashSet<>();
        for (int dx = -PRELOAD_RADIUS; dx <= PRELOAD_RADIUS; dx++) {
            for (int dy = -PRELOAD_RADIUS; dy <= PRELOAD_RADIUS; dy++) {
                requiredChunks.add(new Vector2(chunkX + dx, chunkY + dy));
            }
        }


        for (Vector2 chunkPos : requiredChunks) {
            if (!preloadedChunks.contains(chunkPos)) {
                preloadChunk(chunkPos);
            }
        }


        for (int dx = -VISIBLE_RADIUS; dx <= VISIBLE_RADIUS; dx++) {
            for (int dy = -VISIBLE_RADIUS; dy <= VISIBLE_RADIUS; dy++) {
                Vector2 visibleChunk = new Vector2(chunkX + dx, chunkY + dy);
                if (!chunkFadeStates.containsKey(visibleChunk)) {
                    chunkFadeStates.put(visibleChunk, 0f);
                }
            }
        }
    }

    private void preloadChunk(Vector2 chunkPos) {
        // Check if chunk is already being loaded or loaded
        if (loadingChunks.containsKey(chunkPos) || worldService.isChunkLoaded(chunkPos)) {
            return;
        }

        // Check if we recently tried to load this chunk and failed
        long now = System.currentTimeMillis();
        Long lastAttempt = chunkLoadAttempts.get(chunkPos);
        if (lastAttempt != null && now - lastAttempt < CHUNK_LOAD_TIMEOUT_MS) {
            log.debug("Skipping chunk load attempt for {} - too soon after failure", chunkPos);
            return;
        }

        // Start loading the chunk
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                worldService.loadChunk(chunkPos);
                preloadedChunks.add(chunkPos);
                chunkLoadAttempts.remove(chunkPos);
                log.debug("Successfully loaded chunk at {}", chunkPos);
            } catch (Exception e) {
                chunkLoadAttempts.put(chunkPos, now);
                log.error("Failed to load chunk at {}: {}", chunkPos, e.getMessage());
                throw e;
            }
        }, executorService).whenComplete((result, ex) -> {
            loadingChunks.remove(chunkPos);
            if (ex != null) {
                log.error("Chunk loading failed for {}: {}", chunkPos, ex.getMessage());
            }
        });

        loadingChunks.put(chunkPos, future);
        log.debug("Started loading chunk at {}", chunkPos);
    }

    private void preloadChunks(int centerX, int centerY) {
        Set<Vector2> requiredChunks = new HashSet<>();
        Set<Vector2> loadedChunks = new HashSet<>();

        // Calculate required chunks in a spiral pattern from center
        for (int radius = 1; radius <= PRELOAD_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    if (Math.abs(dx) == radius || Math.abs(dy) == radius) {
                        requiredChunks.add(new Vector2(centerX + dx, centerY + dy));
                    }
                }
            }
        }

        // First check which chunks are already loaded
        for (Vector2 chunkPos : requiredChunks) {
            if (worldService.isChunkLoaded(chunkPos)) {
                loadedChunks.add(chunkPos);
            }
        }

        // Remove loaded chunks from required set
        requiredChunks.removeAll(loadedChunks);

        // Load remaining chunks with priority based on distance from center
        requiredChunks.stream()
            .sorted((a, b) -> {
                float distA = Vector2.dst(centerX, centerY, a.x, a.y);
                float distB = Vector2.dst(centerX, centerY, b.x, b.y);
                return Float.compare(distA, distB);
            })
            .forEach(this::preloadChunk);
    }

    public void dispose() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
    }
}