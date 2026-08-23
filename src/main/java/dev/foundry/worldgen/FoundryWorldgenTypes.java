package dev.foundry.worldgen;

import com.mojang.serialization.Codec;
import dev.foundry.Foundry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** Registers Foundry-owned worldgen codecs used by the built-in compact geography pack. */
public final class FoundryWorldgenTypes {
    private static final DeferredRegister<Codec<? extends DensityFunction>> DENSITY_FUNCTION_TYPES =
            DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, Foundry.MOD_ID);

    public static final RegistryObject<Codec<? extends DensityFunction>> STRATEGIC_MACRO_MASK =
            DENSITY_FUNCTION_TYPES.register("strategic_macro_mask", StrategicMacroMask.CODEC::codec);

    private FoundryWorldgenTypes() {
    }

    public static void register(IEventBus eventBus) {
        DENSITY_FUNCTION_TYPES.register(eventBus);
    }
}
