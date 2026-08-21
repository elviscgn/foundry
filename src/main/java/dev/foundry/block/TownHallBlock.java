package dev.foundry.block;

import dev.foundry.settlement.Settlement;
import dev.foundry.settlement.SettlementSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class TownHallBlock extends Block {
    public TownHallBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        if (level instanceof ServerLevel serverLevel) {
            SettlementSavedData.get(serverLevel).getOrCreate(serverLevel.dimension(), pos);
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.CONSUME;
        }

        Settlement settlement = SettlementSavedData.get(serverLevel)
                .getOrCreate(serverLevel.dimension(), pos);
        player.displayClientMessage(statusMessage(settlement), false);
        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (state.getBlock() != newState.getBlock() && level instanceof ServerLevel serverLevel) {
            SettlementSavedData.get(serverLevel).remove(serverLevel.dimension(), pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    private static Component statusMessage(Settlement settlement) {
        String supplyState = settlement.isSupplied() ? "SUPPLIED" : "NEEDS BREAD";
        ChatFormatting stateColor = settlement.isSupplied() ? ChatFormatting.GREEN : ChatFormatting.YELLOW;

        Component message = Component.literal("Town Hall | Population: " + settlement.getPopulation() + " | Bread: ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(settlement.getBreadSupplied() + "/" + settlement.getBreadTarget()))
                .append(Component.literal(" (-" + settlement.getDailyBreadConsumption() + "/day)").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" | " + supplyState).withStyle(stateColor))
                .append(Component.literal(" | Prosperity: " + settlement.getProsperity()).withStyle(ChatFormatting.AQUA));

        if (!settlement.isSupplied()) {
            message = message.copy().append(Component.literal(" | Deliver at Freight Depot").withStyle(ChatFormatting.GRAY));
        }
        return message;
    }
}
