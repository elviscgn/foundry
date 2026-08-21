package dev.foundry.block;

import dev.foundry.settlement.IndustryType;
import dev.foundry.settlement.Settlement;
import dev.foundry.settlement.SettlementSavedData;
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

public final class IndustryBlock extends Block {
    private final IndustryType industryType;

    public IndustryBlock(Properties properties, IndustryType industryType) {
        super(properties);
        this.industryType = industryType;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel serverLevel) {
            SettlementSavedData.get(serverLevel).registerIndustry(serverLevel.dimension(), pos, industryType);
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
                .registerIndustry(serverLevel.dimension(), pos, industryType);
        if (settlement == null) {
            player.displayClientMessage(
                    Component.literal(industryType.displayName() + " // No Town Hall within 128 blocks"),
                    true
            );
            return InteractionResult.CONSUME;
        }

        int sectorJobs;
        int sectorEmployed;
        if (industryType == IndustryType.BAKERY) {
            sectorJobs = settlement.getFoodJobCapacity();
            sectorEmployed = settlement.getFoodEmployed();
        } else {
            sectorJobs = settlement.getConstructionJobCapacity();
            sectorEmployed = settlement.getConstructionEmployed();
        }

        player.displayClientMessage(
                Component.literal(industryType.displayName()
                        + " // Linked // " + industryType.sectorName()
                        + " sector " + sectorEmployed + "/" + sectorJobs + " staffed"),
                true
        );
        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (state.getBlock() != newState.getBlock() && level instanceof ServerLevel serverLevel) {
            SettlementSavedData.get(serverLevel).removeIndustry(serverLevel.dimension(), pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
