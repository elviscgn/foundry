package dev.foundry.registry;

import dev.foundry.Foundry;
import dev.foundry.block.FreightDepotBlock;
import dev.foundry.block.IndustryBlock;
import dev.foundry.block.TownHallBlock;
import dev.foundry.settlement.IndustryType;
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

    public static final RegistryObject<Block> FREIGHT_DEPOT = BLOCKS.register(
            "freight_depot",
            () -> new FreightDepotBlock(BlockBehaviour.Properties.copy(Blocks.COPPER_BLOCK))
    );

    public static final RegistryObject<Block> BAKERY = BLOCKS.register(
            "bakery",
            () -> new IndustryBlock(BlockBehaviour.Properties.copy(Blocks.BRICKS), IndustryType.BAKERY)
    );

    public static final RegistryObject<Block> BRICKWORKS = BLOCKS.register(
            "brickworks",
            () -> new IndustryBlock(BlockBehaviour.Properties.copy(Blocks.DEEPSLATE_BRICKS), IndustryType.BRICKWORKS)
    );

    private ModBlocks() {
    }

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }
}
