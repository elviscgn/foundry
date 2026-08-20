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
import net.minecraft.world.item.Items;
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

        SettlementSavedData savedData = SettlementSavedData.get(serverLevel);
        Settlement settlement = savedData.getOrCreate(serverLevel.dimension(), pos);
        ItemStack heldStack = player.getItemInHand(hand);

        if (heldStack.is(Items.BREAD) && settlement.getBreadSupplied() < Settlement.BREAD_TARGET) {
            int accepted = settlement.deliverBread(heldStack.getCount());
            if (accepted > 0) {
                if (!player.getAbilities().instabuild) {
                    heldStack.shrink(accepted);
                }
                savedData.setDirty();
                player.displayClientMessage(deliveryMessage(settlement, accepted), false);
                return InteractionResult.CONSUME;
            }
        }

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

    private static Component deliveryMessage(Settlement settlement, int accepted) {
        String supplyState = settlement.isSupplied() ? "SUPPLIED" : "NEEDS BREAD";
        ChatFormatting stateColor = settlement.isSupplied() ? ChatFormatting.GREEN : ChatFormatting.YELLOW;

        return Component.literal("Delivered " + accepted + " bread. ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(settlement.getBreadSupplied() + "/" + Settlement.BREAD_TARGET + " — "))
                .append(Component.literal(supplyState).withStyle(stateColor))
                .append(Component.literal(" | Prosperity: " + settlement.getProsperity()).withStyle(ChatFormatting.AQUA));
    }

    private static Component statusMessage(Settlement settlement) {
        String supplyState = settlement.isSupplied() ? "SUPPLIED" : "NEEDS BREAD";
        ChatFormatting stateColor = settlement.isSupplied() ? ChatFormatting.GREEN : ChatFormatting.YELLOW;

        return Component.literal("Town Hall | Population: " + settlement.getPopulation() + " | Bread: ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(settlement.getBreadSupplied() + "/" + Settlement.BREAD_TARGET))
                .append(Component.literal(" | " + supplyState).withStyle(stateColor))
                .append(Component.literal(" | Prosperity: " + settlement.getProsperity()).withStyle(ChatFormatting.AQUA));
    }
}
