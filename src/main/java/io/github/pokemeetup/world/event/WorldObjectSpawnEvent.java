package io.github.pokemeetup.world.event;

import io.github.pokemeetup.world.model.WorldObject;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class WorldObjectSpawnEvent extends ApplicationEvent {
    private final WorldObject worldObject;

    public WorldObjectSpawnEvent(Object source, WorldObject worldObject) {
        super(source);
        this.worldObject = worldObject;
    }
}
