package dev.foundry.registry;

import dev.foundry.Foundry;
import dev.foundry.block.TownHallBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks {
    private static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, Foundry.MOD_ID);

    public static final RegistryObject<Block> TOWN_HALL = BLOCKS.register(
            "town_hall",
            () -> new TownHallBlock(BlockBehaviour.Properties.copy(Blocks.STONE_BRICKS))
    );

    private ModBlocks() {
    }

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }
}
