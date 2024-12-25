package io.github.pokemeetup.multiplayer.model;

import lombok.Getter;
import lombok.Setter;
import io.github.pokemeetup.world.model.WorldObject;
import java.util.List;

@Getter @Setter
public class ChunkUpdate {
    private int chunkX;
    private int chunkY;
    private int[][] tiles;
    private List<WorldObject> objects;
}
