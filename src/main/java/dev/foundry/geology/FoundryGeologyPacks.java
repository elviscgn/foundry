package dev.foundry.geology;

import dev.foundry.Foundry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.fml.ModList;

import java.nio.file.Path;

/**
 * Loads Tiger Ascent's geology balance above Create Ore Excavation's built-in recipes.
 *
 * <p>COE remains the extraction/finder substrate. Foundry owns strategic resource density,
 * finite lava reservoirs and the travel scale at which deposits enter the national economy.</p>
 */
public final class FoundryGeologyPacks {
    private static final String PACK_PATH = "resourcepacks/foundry_geology";
    private static final String PACK_ID = "builtin/foundry_geology";

    private FoundryGeologyPacks() {
    }

    public static void register(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) {
            return;
        }

        Path resourcePath = ModList.get()
                .getModFileById(Foundry.MOD_ID)
                .getFile()
                .findResource(PACK_PATH);
        Pack.ResourcesSupplier supplier = id -> new PathPackResources(id, resourcePath, false);
        Pack.Info info = Pack.readPackInfo(PACK_ID, supplier);
        if (info == null) {
            throw new IllegalStateException("Foundry geology pack metadata could not be read");
        }

        // Required and TOP: these recipe IDs deliberately replace COE's much wider default
        // placement grids. This avoids relying on mod resource-pack ordering.
        Pack pack = Pack.create(
                PACK_ID,
                Component.literal("Foundry Strategic Geology"),
                true,
                supplier,
                info,
                PackType.SERVER_DATA,
                Pack.Position.TOP,
                true,
                PackSource.BUILT_IN
        );
        event.addRepositorySource(consumer -> consumer.accept(pack));
    }
}
