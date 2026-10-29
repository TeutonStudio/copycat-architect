package de.teutonstudio.copycatarchitect.integration;

import java.util.Map;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Captures Minecraft's generic Property<T> while applying validated source states. */
public final class SourceStates {
    private SourceStates() {}
    public static BlockState apply(BlockState state, Map<String, String> values) {
        for (var entry : values.entrySet()) {
            var property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (property == null) throw new IllegalArgumentException("Unknown source property: " + entry.getKey());
            state = set(state, property, entry.getValue());
        }
        return state;
    }
    private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> property, String value) {
        return state.setValue(property, property.getValue(value).orElseThrow(() -> new IllegalArgumentException("Invalid source state: " + value)));
    }
}
