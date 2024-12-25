package io.github.pokemeetup.world.model;

import com.badlogic.gdx.math.Rectangle;
import jakarta.persistence.*;
import lombok.Data;

import java.util.UUID;

@Embeddable
@Data
public class WorldObject {

    private String id;

    private int tileX;
    private int tileY;

    @Enumerated(EnumType.STRING)
    private ObjectType type;

    private float spawnTime;
    private boolean collidable;

    
    private float timeSinceVisible;

    public WorldObject() {

    }

    public WorldObject(int tileX, int tileY, ObjectType type, boolean collidable) {
        this.tileX = tileX;
        this.tileY = tileY;
        this.type = type;
        this.collidable = collidable;
        this.id = UUID.randomUUID().toString();
        this.spawnTime = type.isPermanent() ? 0f : (System.currentTimeMillis() / 1000f);
        this.timeSinceVisible = 0f;
    }

    
    @Transient
    public float getFadeAlpha() {
        return Math.min(timeSinceVisible, 1f);
    }

    
    @Transient
    public Rectangle getCollisionBox() {
        if (!collidable) {
            return null;
        }

        float pixelX = tileX * 32;
        float pixelY = tileY * 32;

        if (isTreeType(this.type)) {
            float baseX = pixelX - 32;
            return new Rectangle(baseX, pixelY, this.type.getWidthInTiles() * 32, 32);
        } else {
            return new Rectangle(
                    pixelX,
                    pixelY,
                    this.type.getWidthInTiles() * 32,
                    this.type.getHeightInTiles() * 32
            );
        }
    }


    private boolean isTreeType(ObjectType t) {
        return t == ObjectType.TREE_0 ||
                t == ObjectType.TREE_1 ||
                t == ObjectType.SNOW_TREE ||
                t == ObjectType.HAUNTED_TREE ||
                t == ObjectType.RUINS_TREE ||
                t == ObjectType.APRICORN_TREE ||
                t == ObjectType.RAIN_TREE ||
                t == ObjectType.CHERRY_TREE;
    }
}
