package dev.foundry.block;

import dev.foundry.block.entity.FreightDepotBlockEntity;
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
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class FreightDepotBlock extends BaseEntityBlock {
    public FreightDepotBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FreightDepotBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        if (level instanceof ServerLevel serverLevel) {
            SettlementSavedData.get(serverLevel).linkDepot(serverLevel.dimension(), pos);
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
        Settlement settlement = savedData.getSettlementForDepot(serverLevel.dimension(), pos);
        if (settlement == null) {
            settlement = savedData.linkDepot(serverLevel.dimension(), pos);
        }

        if (settlement == null) {
            player.displayClientMessage(
                    Component.literal("Freight Depot | No Town Hall within 128 blocks")
                            .withStyle(ChatFormatting.RED),
                    false
            );
            return InteractionResult.CONSUME;
        }

        ItemStack heldStack = player.getItemInHand(hand);
        int accepted = 0;
        String commodity = null;

        if (heldStack.is(Items.BREAD)) {
            accepted = settlement.deliverBread(heldStack.getCount());
            commodity = "bread";
        } else if (heldStack.is(Items.BRICK)) {
            accepted = settlement.deliverBuildingMaterials(heldStack.getCount());
            commodity = "bricks";
        }

        if (accepted > 0 && commodity != null) {
            if (!player.getAbilities().instabuild) {
                heldStack.shrink(accepted);
            }
            savedData.setDirty();
            player.displayClientMessage(deliveryMessage(settlement, accepted, commodity), false);
            return InteractionResult.CONSUME;
        }

        player.displayClientMessage(statusMessage(settlement), false);
        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (state.getBlock() != newState.getBlock() && level instanceof ServerLevel serverLevel) {
            SettlementSavedData.get(serverLevel).removeDepot(serverLevel.dimension(), pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    private static Component deliveryMessage(Settlement settlement, int accepted, String commodity) {
        return Component.literal("Freight Depot | Accepted " + accepted + " " + commodity + " | ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal("Bread " + settlement.getBreadSupplied() + "/" + settlement.getBreadTarget()))
                .append(Component.literal(" | Bricks " + settlement.getBuildingMaterialsSupplied()
                        + "/" + settlement.getBuildingMaterialsTarget()).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" | Prosperity " + settlement.getProsperity()).withStyle(ChatFormatting.AQUA));
    }

    private static Component statusMessage(Settlement settlement) {
        String growthState = settlement.isGrowthReady() ? "GROWTH READY" : "STABLE";
        ChatFormatting growthColor = settlement.isGrowthReady() ? ChatFormatting.GREEN : ChatFormatting.YELLOW;

        return Component.literal("Freight Depot | Linked | Bread ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(settlement.getBreadSupplied() + "/" + settlement.getBreadTarget()))
                .append(Component.literal(" (-" + settlement.getDailyBreadConsumption() + "/day)").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" | Bricks " + settlement.getBuildingMaterialsSupplied()
                        + "/" + settlement.getBuildingMaterialsTarget()).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" | " + growthState).withStyle(growthColor))
                .append(Component.literal(" | Prosperity " + settlement.getProsperity()).withStyle(ChatFormatting.AQUA));
    }
}
