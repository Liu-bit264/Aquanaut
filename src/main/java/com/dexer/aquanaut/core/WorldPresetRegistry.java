package com.dexer.aquanaut.core;

import com.dexer.aquanaut.Aquanaut;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * Keys for the world presets this mod contributes through data packs.
 *
 * <p>
 * The water world itself is pure data: {@code data/aquanaut/worldgen/world_preset/water_world.json}
 * pairs an all-deep-ocean biome source with {@code aquanaut:water_world} noise settings whose
 * continents function is pinned to a constant deep-ocean value, so every column is open water and
 * the mod's ocean layer stack qualifies everywhere. The {@code minecraft:normal} world preset tag
 * adds it to the world type cycle on the create-world screen, and the translation key
 * {@code generator.aquanaut.water_world} names it.
 */
public final class WorldPresetRegistry {
    public static final ResourceKey<WorldPreset> WATER_WORLD = ResourceKey.create(Registries.WORLD_PRESET,
            ResourceLocation.fromNamespaceAndPath(Aquanaut.MODID, "water_world"));
    /**
     * The water world's noise settings reuse the preset's id. Every "is this the water
     * world?" check in the mod is really a comparison against this one id, so it is named
     * here once instead of being respelled at each site.
     */
    public static final ResourceLocation WATER_WORLD_SETTINGS =
            ResourceLocation.fromNamespaceAndPath(Aquanaut.MODID, "water_world");

    private WorldPresetRegistry() {
    }

    /** Whether a generator's noise settings are the water world's. */
    public static boolean isWaterWorld(Holder<NoiseGeneratorSettings> settings) {
        return settings.unwrapKey()
                .map(key -> key.location().equals(WATER_WORLD_SETTINGS))
                .orElse(false);
    }
}
