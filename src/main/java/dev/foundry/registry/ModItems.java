package dev.foundry.registry;

import dev.foundry.Foundry;
import dev.foundry.item.SurveyorsRodItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Foundry.MOD_ID);

    public static final RegistryObject<Item> TOWN_HALL = blockItem("town_hall", ModBlocks.TOWN_HALL);
    public static final RegistryObject<Item> FREIGHT_DEPOT = blockItem("freight_depot", ModBlocks.FREIGHT_DEPOT);
    public static final RegistryObject<Item> BAKERY = blockItem("bakery", ModBlocks.BAKERY);
    public static final RegistryObject<Item> BRICKWORKS = blockItem("brickworks", ModBlocks.BRICKWORKS);
    public static final RegistryObject<Item> WAREHOUSE = blockItem("warehouse", ModBlocks.WAREHOUSE);
    public static final RegistryObject<Item> NATIONAL_STATISTICS_BUREAU =
            blockItem("national_statistics_bureau", ModBlocks.NATIONAL_STATISTICS_BUREAU);

    public static final RegistryObject<Item> SURVEYORS_ROD = ITEMS.register(
            "surveyors_rod", () -> new SurveyorsRodItem(new Item.Properties()));

    private static RegistryObject<Item> blockItem(String name, RegistryObject<? extends net.minecraft.world.level.block.Block> block) {
        return ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    private ModItems() { }
    public static void register(IEventBus eventBus) { ITEMS.register(eventBus); }
}
