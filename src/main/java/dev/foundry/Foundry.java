package dev.foundry;

import dev.foundry.network.FoundryNetwork;
import dev.foundry.registry.ModBlockEntities;
import dev.foundry.registry.ModBlocks;
import dev.foundry.registry.ModItems;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(Foundry.MOD_ID)
public final class Foundry {
    public static final String MOD_ID = "foundry";

    public Foundry(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        FoundryNetwork.register();
        modEventBus.addListener(this::addCreativeTabContents);
    }

    private void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.TOWN_HALL.get());
            event.accept(ModItems.FREIGHT_DEPOT.get());
            event.accept(ModItems.BAKERY.get());
            event.accept(ModItems.BRICKWORKS.get());
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.SURVEYORS_ROD.get());
        }
    }
}
