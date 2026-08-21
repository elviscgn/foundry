package dev.foundry.registry;

import dev.foundry.Foundry;
import dev.foundry.block.entity.FreightDepotBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlockEntities {
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Foundry.MOD_ID);

    public static final RegistryObject<BlockEntityType<FreightDepotBlockEntity>> FREIGHT_DEPOT =
            BLOCK_ENTITIES.register(
                    "freight_depot",
                    () -> BlockEntityType.Builder.of(
                            FreightDepotBlockEntity::new,
                            ModBlocks.FREIGHT_DEPOT.get()
                    ).build(null)
            );

    private ModBlockEntities() {
    }

    public static void register(IEventBus eventBus) {
        BLOCK_ENTITIES.register(eventBus);
    }
}
