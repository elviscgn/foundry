package dev.foundry.block;

import dev.foundry.block.entity.FreightDepotBlockEntity;
import dev.foundry.registry.ModBlockEntities;
import dev.foundry.settlement.IndustryType;
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
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.UUID;

public final class FreightDepotBlock extends BaseEntityBlock {
    private static final long TICKS_PER_DAY = 24_000L;

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
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(
                type,
                ModBlockEntities.FREIGHT_DEPOT.get(),
                FreightDepotBlockEntity::serverTick
        );
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        if (level instanceof ServerLevel serverLevel) {
            SettlementSavedData.get(serverLevel).linkDepot(serverLevel.dimension(), pos);
            if (serverLevel.getBlockEntity(pos) instanceof FreightDepotBlockEntity depot) {
                depot.refreshGoggleState();
            }
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

        FreightDepotBlockEntity depot = serverLevel.getBlockEntity(pos) instanceof FreightDepotBlockEntity foundryDepot
                ? foundryDepot
                : null;
        if (depot != null) {
            depot.refreshGoggleState();
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
        if (player.isShiftKeyDown() && heldStack.isEmpty()) {
            SettlementSavedData.IndustryLinkResult result = savedData.completeIndustryDepotLink(
                    player.getUUID(),
                    serverLevel.dimension(),
                    pos
            );
            if (result.handled()) {
                player.displayClientMessage(
                        Component.literal(result.message())
                                .withStyle(result.success() ? ChatFormatting.GREEN : ChatFormatting.RED),
                        false
                );
                if (depot != null) {
                    depot.refreshGoggleState();
                }
                return InteractionResult.CONSUME;
            }

            if (depot != null) {
                String mode = depot.cycleOperatingMode();
                player.displayClientMessage(
                        Component.literal("Freight Depot // MODE " + mode.toUpperCase())
                                .withStyle(ChatFormatting.GOLD),
                        true
                );
                depot.refreshGoggleState();
            }
            return InteractionResult.CONSUME;
        }

        int accepted = 0;
        String commodity = null;
        IndustryType industryType = null;

        if (depot == null || depot.isIntakeMode()) {
            if (heldStack.is(Items.BREAD)) {
                accepted = settlement.deliverBread(heldStack.getCount());
                commodity = "bread";
                industryType = IndustryType.BAKERY;
            } else if (heldStack.is(Items.BRICK)) {
                accepted = settlement.deliverBuildingMaterials(heldStack.getCount());
                commodity = "bricks";
                industryType = IndustryType.BRICKWORKS;
            }
        }

        if (accepted > 0 && commodity != null) {
            UUID originSettlementId = FreightDepotBlockEntity.getDomesticTradeOrigin(heldStack);
            if (originSettlementId != null) {
                long currentDay = Math.floorDiv(serverLevel.getDayTime(), TICKS_PER_DAY);
                savedData.recordDomesticImport(originSettlementId, settlement, industryType, accepted, currentDay);
            }

            if (!player.getAbilities().instabuild) {
                heldStack.shrink(accepted);
            }
            savedData.setDirty();
            if (depot != null) {
                depot.refreshGoggleState();
            }
            player.displayClientMessage(deliveryMessage(settlement, accepted, commodity), false);
            return InteractionResult.CONSUME;
        }

        player.displayClientMessage(statusMessage(settlement, depot == null ? "Intake" : depot.getOperatingModeLabel()), false);
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

    private static Component statusMessage(Settlement settlement, String mode) {
        String growthState = settlement.isGrowthReady() ? "GROWTH READY" : "STABLE";
        ChatFormatting growthColor = settlement.isGrowthReady() ? ChatFormatting.GREEN : ChatFormatting.YELLOW;

        return Component.literal("Freight Depot | " + mode + " | Bread ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(settlement.getBreadSupplied() + "/" + settlement.getBreadTarget()))
                .append(Component.literal(" (-" + settlement.getDailyBreadConsumption() + "/day)").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" | Bricks " + settlement.getBuildingMaterialsSupplied()
                        + "/" + settlement.getBuildingMaterialsTarget()).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" | Trade B +" + settlement.getBreadImportsToday()
                        + "/-" + settlement.getBreadExportsToday()).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" R +" + settlement.getBrickImportsToday()
                        + "/-" + settlement.getBrickExportsToday()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" | " + growthState).withStyle(growthColor))
                .append(Component.literal(" | Prosperity " + settlement.getProsperity()).withStyle(ChatFormatting.AQUA));
    }
}
