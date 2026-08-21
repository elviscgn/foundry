package dev.foundry.block;

import dev.foundry.network.FoundryNetwork;
import dev.foundry.settlement.Settlement;
import dev.foundry.settlement.SettlementSavedData;
import dev.foundry.settlement.SettlementTier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.CONSUME;
        }

        SettlementSavedData data = SettlementSavedData.get(serverLevel);
        Settlement settlement = data.getOrCreate(serverLevel.dimension(), pos);

        if (serverPlayer.isShiftKeyDown()) {
            settlement.cycleLaborPriority();
            data.setDirty();
            data.refreshIndustrySignals(serverLevel.getServer());
            serverPlayer.displayClientMessage(
                    Component.literal("Labor priority // " + settlement.getLaborPriority().displayName()),
                    true
            );
            return InteractionResult.CONSUME;
        }

        SettlementTier tier = data.getSettlementTier(settlement);
        serverPlayer.displayClientMessage(
                Component.literal(tier.displayName() + " // Territory radius " + tier.claimRadius() + " blocks"),
                true
        );
        FoundryNetwork.sendSettlementSnapshot(serverPlayer, settlement);
        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (state.getBlock() != newState.getBlock() && level instanceof ServerLevel serverLevel) {
            SettlementSavedData.get(serverLevel).remove(serverLevel.dimension(), pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
