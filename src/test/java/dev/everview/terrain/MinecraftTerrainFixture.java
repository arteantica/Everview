package dev.everview.terrain;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.*;

/** The game loads data-pack tags after static bootstrap. 26.3 heightmap floor
 * predicates use these tags; bootstrap alone silently returns the minimum Y. */
public final class MinecraftTerrainFixture {
    private static boolean ready;
    private MinecraftTerrainFixture() {}
    public static synchronized void bootstrap() {
        if (ready) return;
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var vanilla=new VanillaPackResourcesBuilder().pushJarResources().exposeNamespace("minecraft")
                .build(new PackLocationInfo("terrain-test-vanilla",Component.literal("Vanilla"),PackSource.BUILT_IN,Optional.empty()));
        try(var resources=new MultiPackResourceManager(PackType.SERVER_DATA,List.of(vanilla.fullResources()))) {
            var pending=TagLoader.loadTagsForExistingRegistries(resources,
                    RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            if(pending.isEmpty())throw new AssertionError("packaged vanilla tags were not loaded");
            pending.forEach(tags->tags.apply());
        }
        var floor=Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
        if(!floor.test(Blocks.STONE.defaultBlockState()) || floor.test(Blocks.WATER.defaultBlockState()))
            throw new AssertionError("heightmap tags must recognize solid stone and exclude water");
        ready=true;
    }
}
