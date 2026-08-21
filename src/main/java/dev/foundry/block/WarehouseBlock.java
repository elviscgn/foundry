package dev.foundry.block;

import dev.foundry.settlement.Settlement;
import dev.foundry.settlement.SettlementIdentity;
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

public final class WarehouseBlock extends Block {
    private static final long TICKS_PER_DAY = 24_000L;

    public WarehouseBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel serverLevel) {
            SettlementSavedData.get(serverLevel).registerWarehouse(serverLevel.dimension(), pos);
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.CONSUME;

        SettlementSavedData data = SettlementSavedData.get(serverLevel);
        SettlementSavedData.WarehouseStatus status = data.getWarehouseStatus(serverLevel.dimension(), pos);
        if (!status.linked()) status = data.registerWarehouse(serverLevel.dimension(), pos);
        if (!status.linked()) {
            player.displayClientMessage(Component.literal("Warehouse // UNLINKED // Place inside settlement territory")
                    .withStyle(ChatFormatting.RED), false);
            return InteractionResult.CONSUME;
        }

        if (!status.commissioned()) {
            long day = Math.floorDiv(serverLevel.getDayTime(), TICKS_PER_DAY);
            status = data.commissionWarehouse(serverLevel.dimension(), pos, day);
        }

        Settlement settlement = status.settlement();
        String label = SettlementIdentity.label(settlement, data.getSettlementTier(settlement));
        if (!status.commissioned()) {
            String reason = status.failure().isBlank() ? "NOT COMMISSIONED" : status.failure();
            player.displayClientMessage(Component.literal("Warehouse // " + label + " // " + reason
                            + " // Cost K" + SettlementSavedData.WAREHOUSE_KORA_COST
                            + " + " + SettlementSavedData.WAREHOUSE_BRICK_COST + " Bricks")
                    .withStyle(ChatFormatting.YELLOW), false);
            return InteractionResult.CONSUME;
        }

        player.displayClientMessage(Component.literal("Warehouse // " + label + " // COMMISSIONED // "
                        + status.commissionedWarehouses() + " active // Bread capacity " + status.breadCapacity()
                        + " // Brick capacity " + status.brickCapacity())
                .withStyle(ChatFormatting.GREEN), false);
        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (state.getBlock() != newState.getBlock() && level instanceof ServerLevel serverLevel) {
            SettlementSavedData.get(serverLevel).removeWarehouse(serverLevel.dimension(), pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
