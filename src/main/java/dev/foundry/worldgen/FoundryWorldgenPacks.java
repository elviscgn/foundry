package dev.foundry.worldgen;

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
 * Registers Foundry's mandatory world-generation overrides above Tectonic's built-in data pack.
 * Tectonic remains the terrain substrate; Foundry owns the national-scale geography tuning.
 */
public final class FoundryWorldgenPacks {
    private static final String COMPACT_WORLDGEN_PATH = "resourcepacks/foundry_compact_worldgen";
    private static final String COMPACT_WORLDGEN_ID = "builtin/foundry_compact_worldgen";

    private FoundryWorldgenPacks() {
    }

    public static void register(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) {
            return;
        }

        Path resourcePath = ModList.get()
                .getModFileById(Foundry.MOD_ID)
                .getFile()
                .findResource(COMPACT_WORLDGEN_PATH);
        Pack.ResourcesSupplier supplier = id -> new PathPackResources(id, resourcePath, false);
        Pack.Info info = Pack.readPackInfo(COMPACT_WORLDGEN_ID, supplier);
        if (info == null) {
            return;
        }

        // Required + fixed at TOP so Tectonic's own built-in pack cannot silently replace
        // Foundry's national-scale noise-router entry during world creation/reload.
        Pack pack = Pack.create(
                COMPACT_WORLDGEN_ID,
                Component.literal("Foundry Compact National Worldgen"),
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
