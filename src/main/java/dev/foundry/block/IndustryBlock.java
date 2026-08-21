package dev.foundry.block;

import dev.foundry.settlement.IndustryType;
import dev.foundry.settlement.Settlement;
import dev.foundry.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;

public final class IndustryBlock extends Block {
    public static final IntegerProperty STAFFING = IntegerProperty.create("staffing", 0, 15);
    private static final long TICKS_PER_DAY = 24_000L;

    private final IndustryType industryType;

    public IndustryBlock(Properties properties, IndustryType industryType) {
        super(properties);
        this.industryType = industryType;
        registerDefaultState(stateDefinition.any().setValue(STAFFING, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(STAFFING);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel serverLevel) {
            Settlement settlement = SettlementSavedData.get(serverLevel)
                    .registerIndustry(serverLevel.dimension(), pos, industryType);
            if (settlement != null) {
                syncStaffing(serverLevel, pos, settlement.getIndustryStaffingSignal(industryType));
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
        if (player.isShiftKeyDown() && player.getItemInHand(hand).isEmpty()) {
            SettlementSavedData.IndustryLinkResult result = savedData.beginIndustryDepotLink(
                    player.getUUID(),
                    serverLevel.dimension(),
                    pos,
                    industryType
            );
            player.displayClientMessage(Component.literal(result.message()), true);
            return InteractionResult.CONSUME;
        }

        Settlement settlement = savedData.registerIndustry(serverLevel.dimension(), pos, industryType);
        if (settlement == null) {
            syncStaffing(serverLevel, pos, 0);
            player.displayClientMessage(
                    Component.literal(industryType.displayName() + " // UNLINKED // No Town Hall within 128 blocks"),
                    true
            );
            return InteractionResult.CONSUME;
        }

        int sectorJobs = settlement.getIndustryJobCapacity(industryType);
        int sectorEmployed = settlement.getIndustryEmployed(industryType);
        int staffingSignal = settlement.getIndustryStaffingSignal(industryType);
        int staffingPercent = settlement.getIndustryStaffingPercent(industryType);
        syncStaffing(serverLevel, pos, staffingSignal);

        long currentDay = Math.floorDiv(serverLevel.getDayTime(), TICKS_PER_DAY);
        SettlementSavedData.IndustryTelemetry telemetry = savedData.getIndustryTelemetry(
                serverLevel.dimension(),
                pos,
                currentDay
        );

        String status = staffingSignal >= 15 ? "ACTIVE" : staffingSignal > 0 ? "UNDERSTAFFED" : "IDLE";
        String clutch = staffingSignal >= 15 ? "CLUTCH STOP OFF" : "CLUTCH STOP ON";
        String line = telemetry.depotLinked() ? "LINE LINKED" : "LINE UNLINKED";

        player.displayClientMessage(
                Component.literal(industryType.displayName()
                        + " // " + status
                        + " // " + industryType.sectorName() + " " + sectorEmployed + "/" + sectorJobs
                        + " staffed (" + staffingPercent + "%)"
                        + " // " + line
                        + " // Output " + telemetry.outputToday() + " today, "
                        + telemetry.sectorAverage7d() + "/day 7d"
                        + " // Lifetime " + telemetry.lifetimeOutput()
                        + " // " + clutch),
                true
        );
        return InteractionResult.CONSUME;
    }

    public void syncStaffing(ServerLevel level, BlockPos pos, int staffingSignal) {
        BlockState current = level.getBlockState(pos);
        if (current.getBlock() != this || !current.hasProperty(STAFFING)) {
            return;
        }

        int clamped = Math.max(0, Math.min(15, staffingSignal));
        if (current.getValue(STAFFING) == clamped) {
            return;
        }

        level.setBlock(pos, current.setValue(STAFFING, clamped), Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS);
    }

    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        // Create clutches disengage when powered, so Foundry emits a stop signal while labor is insufficient.
        return state.getValue(STAFFING) >= 15 ? 0 : 15;
    }

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        // Comparator output is the actual staffing level: 0 = idle, 15 = fully staffed.
        return state.getValue(STAFFING);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (state.getBlock() != newState.getBlock() && level instanceof ServerLevel serverLevel) {
            SettlementSavedData.get(serverLevel).removeIndustry(serverLevel.dimension(), pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
