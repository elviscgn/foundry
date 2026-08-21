package dev.foundry.block.entity;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.foundry.registry.ModBlockEntities;
import dev.foundry.registry.ModItems;
import dev.foundry.settlement.IndustryType;
import dev.foundry.settlement.Settlement;
import dev.foundry.settlement.SettlementSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class FreightDepotBlockEntity extends SmartBlockEntity implements IHaveGoggleInformation {
    private static final long TICKS_PER_DAY = 24_000L;
    public static final String TAG_EXPORT_ORIGIN = "FoundryExportOrigin";

    private static final String TAG_OPERATING_MODE = "OperatingMode";
    private static final String TAG_SETTLEMENT_LINKED = "SettlementLinked";
    private static final String TAG_POPULATION = "SyncedPopulation";
    private static final String TAG_PROSPERITY = "SyncedProsperity";
    private static final String TAG_BREAD_SUPPLIED = "SyncedBreadSupplied";
    private static final String TAG_BREAD_TARGET = "SyncedBreadTarget";
    private static final String TAG_DAILY_BREAD = "SyncedDailyBread";
    private static final String TAG_MATERIALS_SUPPLIED = "SyncedMaterialsSupplied";
    private static final String TAG_MATERIALS_TARGET = "SyncedMaterialsTarget";
    private static final String TAG_GROWTH_READY = "SyncedGrowthReady";
    private static final String TAG_FOOD_OUTPUT_TODAY = "SyncedFoodOutputToday";
    private static final String TAG_CONSTRUCTION_OUTPUT_TODAY = "SyncedConstructionOutputToday";
    private static final String TAG_FOOD_OUTPUT_AVERAGE = "SyncedFoodOutputAverage";
    private static final String TAG_CONSTRUCTION_OUTPUT_AVERAGE = "SyncedConstructionOutputAverage";
    private static final String TAG_BREAD_IMPORTS = "SyncedBreadImports";
    private static final String TAG_BREAD_EXPORTS = "SyncedBreadExports";
    private static final String TAG_BRICK_IMPORTS = "SyncedBrickImports";
    private static final String TAG_BRICK_EXPORTS = "SyncedBrickExports";

    private final IItemHandler depotItemHandler = new DepotItemHandler();
    private LazyOptional<IItemHandler> itemHandlerCapability = LazyOptional.of(() -> depotItemHandler);

    private OperatingMode operatingMode = OperatingMode.INTAKE;
    private boolean settlementLinked;
    private int syncedPopulation;
    private int syncedProsperity;
    private int syncedBreadSupplied;
    private int syncedBreadTarget;
    private int syncedDailyBread;
    private int syncedMaterialsSupplied;
    private int syncedMaterialsTarget;
    private boolean syncedGrowthReady;
    private int syncedFoodOutputToday;
    private int syncedConstructionOutputToday;
    private int syncedFoodOutputAverage;
    private int syncedConstructionOutputAverage;
    private int syncedBreadImports;
    private int syncedBreadExports;
    private int syncedBrickImports;
    private int syncedBrickExports;

    public FreightDepotBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FREIGHT_DEPOT.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(new DirectBeltInputBehaviour(this).allowingBeltFunnels());
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, FreightDepotBlockEntity blockEntity) {
        if (level.isClientSide) {
            return;
        }

        blockEntity.tick();
        if (level.getGameTime() % 10L == 0L) {
            blockEntity.refreshGoggleState();
        }
    }

    public String cycleOperatingMode() {
        operatingMode = operatingMode.next();
        setChanged();
        if (level instanceof ServerLevel serverLevel) {
            BlockState state = getBlockState();
            serverLevel.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS);
        }
        return operatingMode.displayName();
    }

    public String getOperatingModeLabel() {
        return operatingMode.displayName();
    }

    public boolean isIntakeMode() {
        return operatingMode == OperatingMode.INTAKE;
    }

    public static UUID getDomesticTradeOrigin(ItemStack stack) {
        if (stack.isEmpty() || !stack.hasTag()) {
            return null;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.hasUUID(TAG_EXPORT_ORIGIN)) {
            return null;
        }
        return tag.getUUID(TAG_EXPORT_ORIGIN);
    }

    public void refreshGoggleState() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        SettlementSavedData savedData = SettlementSavedData.get(serverLevel);
        Settlement settlement = savedData.getSettlementForDepot(serverLevel.dimension(), worldPosition);
        if (settlement == null) {
            settlement = savedData.linkDepot(serverLevel.dimension(), worldPosition);
        }

        boolean changed;
        if (settlement == null) {
            changed = applySnapshot(false, 0, 0, 0, 0, 0, 0, 0, false,
                    0, 0, 0, 0, 0, 0, 0, 0);
        } else {
            changed = applySnapshot(
                    true,
                    settlement.getPopulation(),
                    settlement.getProsperity(),
                    settlement.getBreadSupplied(),
                    settlement.getBreadTarget(),
                    settlement.getDailyBreadConsumption(),
                    settlement.getBuildingMaterialsSupplied(),
                    settlement.getBuildingMaterialsTarget(),
                    settlement.isGrowthReady(),
                    settlement.getFoodOutputToday(),
                    settlement.getConstructionOutputToday(),
                    settlement.getFoodOutputAverage(7),
                    settlement.getConstructionOutputAverage(7),
                    settlement.getBreadImportsToday(),
                    settlement.getBreadExportsToday(),
                    settlement.getBrickImportsToday(),
                    settlement.getBrickExportsToday()
            );
        }

        if (changed) {
            setChanged();
            BlockState state = getBlockState();
            serverLevel.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    private boolean applySnapshot(boolean linked, int population, int prosperity,
                                  int breadSupplied, int breadTarget, int dailyBread,
                                  int materialsSupplied, int materialsTarget, boolean growthReady,
                                  int foodOutputToday, int constructionOutputToday,
                                  int foodOutputAverage, int constructionOutputAverage,
                                  int breadImports, int breadExports, int brickImports, int brickExports) {
        boolean changed = settlementLinked != linked
                || syncedPopulation != population
                || syncedProsperity != prosperity
                || syncedBreadSupplied != breadSupplied
                || syncedBreadTarget != breadTarget
                || syncedDailyBread != dailyBread
                || syncedMaterialsSupplied != materialsSupplied
                || syncedMaterialsTarget != materialsTarget
                || syncedGrowthReady != growthReady
                || syncedFoodOutputToday != foodOutputToday
                || syncedConstructionOutputToday != constructionOutputToday
                || syncedFoodOutputAverage != foodOutputAverage
                || syncedConstructionOutputAverage != constructionOutputAverage
                || syncedBreadImports != breadImports
                || syncedBreadExports != breadExports
                || syncedBrickImports != brickImports
                || syncedBrickExports != brickExports;

        settlementLinked = linked;
        syncedPopulation = population;
        syncedProsperity = prosperity;
        syncedBreadSupplied = breadSupplied;
        syncedBreadTarget = breadTarget;
        syncedDailyBread = dailyBread;
        syncedMaterialsSupplied = materialsSupplied;
        syncedMaterialsTarget = materialsTarget;
        syncedGrowthReady = growthReady;
        syncedFoodOutputToday = foodOutputToday;
        syncedConstructionOutputToday = constructionOutputToday;
        syncedFoodOutputAverage = foodOutputAverage;
        syncedConstructionOutputAverage = constructionOutputAverage;
        syncedBreadImports = breadImports;
        syncedBreadExports = breadExports;
        syncedBrickImports = brickImports;
        syncedBrickExports = brickExports;
        return changed;
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("Freight Depot").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("  Mode: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(operatingMode.displayName())
                        .withStyle(operatingMode == OperatingMode.INTAKE
                                ? ChatFormatting.GREEN : ChatFormatting.GOLD)));

        if (!settlementLinked) {
            tooltip.add(Component.literal("  No linked settlement").withStyle(ChatFormatting.RED));
            tooltip.add(Component.literal("  Town Hall required within 128 blocks")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return true;
        }

        tooltip.add(Component.literal("  Linked settlement").withStyle(ChatFormatting.GREEN));
        tooltip.add(valueLine("Population", syncedPopulation, ChatFormatting.AQUA));
        tooltip.add(stockLine("Bread", syncedBreadSupplied, syncedBreadTarget).copy()
                .append(Component.literal("  -" + syncedDailyBread + "/day").withStyle(ChatFormatting.DARK_GRAY)));
        tooltip.add(stockLine("Bricks", syncedMaterialsSupplied, syncedMaterialsTarget));
        tooltip.add(valueLine("Prosperity", syncedProsperity, ChatFormatting.GOLD));
        tooltip.add(Component.literal("  Measured output today: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("Food " + syncedFoodOutputToday).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("  Construction " + syncedConstructionOutputToday)
                        .withStyle(ChatFormatting.AQUA)));
        tooltip.add(Component.literal("  7d output avg: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("Food " + syncedFoodOutputAverage + "/day")
                        .withStyle(ChatFormatting.GREEN))
                .append(Component.literal("  Construction " + syncedConstructionOutputAverage + "/day")
                        .withStyle(ChatFormatting.AQUA)));
        tooltip.add(Component.literal("  Domestic trade today: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("Bread +" + syncedBreadImports + "/-" + syncedBreadExports)
                        .withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("  Bricks +" + syncedBrickImports + "/-" + syncedBrickExports)
                        .withStyle(ChatFormatting.AQUA)));

        if (operatingMode != OperatingMode.INTAKE) {
            tooltip.add(Component.literal("  Export through a funnel/chute into Create logistics")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        Component growth;
        if (syncedGrowthReady) {
            growth = Component.literal("  Growth: READY").withStyle(ChatFormatting.GREEN);
        } else if (syncedBreadSupplied < syncedBreadTarget) {
            growth = Component.literal("  Growth: FOOD SHORTAGE").withStyle(ChatFormatting.RED);
        } else if (syncedMaterialsSupplied < syncedMaterialsTarget) {
            growth = Component.literal("  Growth: NEEDS BRICKS").withStyle(ChatFormatting.YELLOW);
        } else if (syncedProsperity < Settlement.GROWTH_PROSPERITY_THRESHOLD) {
            growth = Component.literal("  Growth: Prosperity " + syncedProsperity + "/"
                    + Settlement.GROWTH_PROSPERITY_THRESHOLD).withStyle(ChatFormatting.YELLOW);
        } else {
            growth = Component.literal("  Growth: STABLE").withStyle(ChatFormatting.GRAY);
        }
        tooltip.add(growth);
        return true;
    }

    private static Component valueLine(String label, int value, ChatFormatting valueColor) {
        return Component.literal("  " + label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(Integer.toString(value)).withStyle(valueColor));
    }

    private static Component stockLine(String label, int supplied, int target) {
        ChatFormatting valueColor = supplied >= target ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
        return Component.literal("  " + label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(supplied + "/" + target).withStyle(valueColor));
    }

    @Override
    public ItemStack getIcon(boolean isPlayerSneaking) {
        return ModItems.FREIGHT_DEPOT.get().getDefaultInstance();
    }

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        writeSnapshot(tag);
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        readSnapshot(tag);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    private void writeSnapshot(CompoundTag tag) {
        tag.putString(TAG_OPERATING_MODE, operatingMode.serializedName());
        tag.putBoolean(TAG_SETTLEMENT_LINKED, settlementLinked);
        tag.putInt(TAG_POPULATION, syncedPopulation);
        tag.putInt(TAG_PROSPERITY, syncedProsperity);
        tag.putInt(TAG_BREAD_SUPPLIED, syncedBreadSupplied);
        tag.putInt(TAG_BREAD_TARGET, syncedBreadTarget);
        tag.putInt(TAG_DAILY_BREAD, syncedDailyBread);
        tag.putInt(TAG_MATERIALS_SUPPLIED, syncedMaterialsSupplied);
        tag.putInt(TAG_MATERIALS_TARGET, syncedMaterialsTarget);
        tag.putBoolean(TAG_GROWTH_READY, syncedGrowthReady);
        tag.putInt(TAG_FOOD_OUTPUT_TODAY, syncedFoodOutputToday);
        tag.putInt(TAG_CONSTRUCTION_OUTPUT_TODAY, syncedConstructionOutputToday);
        tag.putInt(TAG_FOOD_OUTPUT_AVERAGE, syncedFoodOutputAverage);
        tag.putInt(TAG_CONSTRUCTION_OUTPUT_AVERAGE, syncedConstructionOutputAverage);
        tag.putInt(TAG_BREAD_IMPORTS, syncedBreadImports);
        tag.putInt(TAG_BREAD_EXPORTS, syncedBreadExports);
        tag.putInt(TAG_BRICK_IMPORTS, syncedBrickImports);
        tag.putInt(TAG_BRICK_EXPORTS, syncedBrickExports);
    }

    private void readSnapshot(CompoundTag tag) {
        operatingMode = tag.contains(TAG_OPERATING_MODE, Tag.TAG_STRING)
                ? OperatingMode.fromSerializedName(tag.getString(TAG_OPERATING_MODE))
                : OperatingMode.INTAKE;
        settlementLinked = tag.getBoolean(TAG_SETTLEMENT_LINKED);
        syncedPopulation = tag.getInt(TAG_POPULATION);
        syncedProsperity = tag.getInt(TAG_PROSPERITY);
        syncedBreadSupplied = tag.getInt(TAG_BREAD_SUPPLIED);
        syncedBreadTarget = tag.getInt(TAG_BREAD_TARGET);
        syncedDailyBread = tag.getInt(TAG_DAILY_BREAD);
        syncedMaterialsSupplied = tag.getInt(TAG_MATERIALS_SUPPLIED);
        syncedMaterialsTarget = tag.getInt(TAG_MATERIALS_TARGET);
        syncedGrowthReady = tag.getBoolean(TAG_GROWTH_READY);
        syncedFoodOutputToday = tag.getInt(TAG_FOOD_OUTPUT_TODAY);
        syncedConstructionOutputToday = tag.getInt(TAG_CONSTRUCTION_OUTPUT_TODAY);
        syncedFoodOutputAverage = tag.getInt(TAG_FOOD_OUTPUT_AVERAGE);
        syncedConstructionOutputAverage = tag.getInt(TAG_CONSTRUCTION_OUTPUT_AVERAGE);
        syncedBreadImports = tag.getInt(TAG_BREAD_IMPORTS);
        syncedBreadExports = tag.getInt(TAG_BREAD_EXPORTS);
        syncedBrickImports = tag.getInt(TAG_BRICK_IMPORTS);
        syncedBrickExports = tag.getInt(TAG_BRICK_EXPORTS);
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
        if (capability == ForgeCapabilities.ITEM_HANDLER) {
            return itemHandlerCapability.cast();
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        itemHandlerCapability.invalidate();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        itemHandlerCapability = LazyOptional.of(() -> depotItemHandler);
    }

    private static IndustryType industryTypeFor(ItemStack stack) {
        if (stack.is(Items.BREAD)) {
            return IndustryType.BAKERY;
        }
        if (stack.is(Items.BRICK)) {
            return IndustryType.BRICKWORKS;
        }
        return null;
    }

    private ItemStack createExportStack(Settlement settlement, int amount) {
        if (amount <= 0 || operatingMode == OperatingMode.INTAKE) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = new ItemStack(
                operatingMode == OperatingMode.EXPORT_BREAD ? Items.BREAD : Items.BRICK,
                amount
        );
        stack.getOrCreateTag().putUUID(TAG_EXPORT_ORIGIN, settlement.getId());
        return stack;
    }

    private int exportAvailable(Settlement settlement) {
        return switch (operatingMode) {
            case INTAKE -> 0;
            case EXPORT_BREAD -> settlement.getBreadSupplied();
            case EXPORT_BRICKS -> settlement.getBuildingMaterialsSupplied();
        };
    }

    private int withdrawExport(Settlement settlement, int amount) {
        return switch (operatingMode) {
            case INTAKE -> 0;
            case EXPORT_BREAD -> settlement.withdrawBread(amount);
            case EXPORT_BRICKS -> settlement.withdrawBuildingMaterials(amount);
        };
    }

    private IndustryType exportIndustryType() {
        return switch (operatingMode) {
            case INTAKE -> null;
            case EXPORT_BREAD -> IndustryType.BAKERY;
            case EXPORT_BRICKS -> IndustryType.BRICKWORKS;
        };
    }

    private final class DepotItemHandler implements IItemHandler {
        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot != 0 || operatingMode == OperatingMode.INTAKE || !(level instanceof ServerLevel serverLevel)) {
                return ItemStack.EMPTY;
            }

            SettlementSavedData savedData = SettlementSavedData.get(serverLevel);
            Settlement settlement = savedData.getSettlementForDepot(serverLevel.dimension(), worldPosition);
            if (settlement == null) {
                settlement = savedData.linkDepot(serverLevel.dimension(), worldPosition);
            }
            if (settlement == null) {
                return ItemStack.EMPTY;
            }

            return createExportStack(settlement, Math.min(64, exportAvailable(settlement)));
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot != 0 || operatingMode != OperatingMode.INTAKE
                    || stack.isEmpty() || !isAcceptedCommodity(stack)) {
                return stack;
            }

            if (!(level instanceof ServerLevel serverLevel)) {
                return stack;
            }

            SettlementSavedData savedData = SettlementSavedData.get(serverLevel);
            Settlement settlement = savedData.getSettlementForDepot(serverLevel.dimension(), worldPosition);
            if (settlement == null) {
                settlement = savedData.linkDepot(serverLevel.dimension(), worldPosition);
            }
            if (settlement == null) {
                refreshGoggleState();
                return stack;
            }

            int capacity = remainingCapacity(settlement, stack);
            int accepted = Math.min(capacity, stack.getCount());
            if (accepted <= 0) {
                return stack;
            }

            if (!simulate) {
                accepted = deliver(settlement, stack, accepted);
                if (accepted > 0) {
                    IndustryType industryType = industryTypeFor(stack);
                    long currentDay = Math.floorDiv(serverLevel.getDayTime(), TICKS_PER_DAY);
                    UUID originSettlementId = getDomesticTradeOrigin(stack);
                    if (originSettlementId != null) {
                        savedData.recordDomesticImport(
                                originSettlementId,
                                settlement,
                                industryType,
                                accepted,
                                currentDay
                        );
                    } else {
                        savedData.recordIndustryOutputForDepot(
                                serverLevel.dimension(),
                                worldPosition,
                                industryType,
                                accepted,
                                currentDay
                        );
                    }
                    savedData.setDirty();
                    refreshGoggleState();
                }
            }

            ItemStack remainder = stack.copy();
            remainder.shrink(accepted);
            return remainder;
        }

        private boolean isAcceptedCommodity(ItemStack stack) {
            return stack.is(Items.BREAD) || stack.is(Items.BRICK);
        }

        private int remainingCapacity(Settlement settlement, ItemStack stack) {
            if (stack.is(Items.BREAD)) {
                return Math.max(0, settlement.getBreadTarget() - settlement.getBreadSupplied());
            }
            if (stack.is(Items.BRICK)) {
                return Math.max(0, settlement.getBuildingMaterialsTarget() - settlement.getBuildingMaterialsSupplied());
            }
            return 0;
        }

        private int deliver(Settlement settlement, ItemStack stack, int offered) {
            if (stack.is(Items.BREAD)) {
                return settlement.deliverBread(offered);
            }
            if (stack.is(Items.BRICK)) {
                return settlement.deliverBuildingMaterials(offered);
            }
            return 0;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot != 0 || amount <= 0 || operatingMode == OperatingMode.INTAKE
                    || !(level instanceof ServerLevel serverLevel)) {
                return ItemStack.EMPTY;
            }

            SettlementSavedData savedData = SettlementSavedData.get(serverLevel);
            Settlement settlement = savedData.getSettlementForDepot(serverLevel.dimension(), worldPosition);
            if (settlement == null) {
                settlement = savedData.linkDepot(serverLevel.dimension(), worldPosition);
            }
            if (settlement == null) {
                return ItemStack.EMPTY;
            }

            int requested = Math.min(Math.min(amount, 64), exportAvailable(settlement));
            if (requested <= 0) {
                return ItemStack.EMPTY;
            }

            if (simulate) {
                return createExportStack(settlement, requested);
            }

            int removed = withdrawExport(settlement, requested);
            if (removed <= 0) {
                return ItemStack.EMPTY;
            }

            long currentDay = Math.floorDiv(serverLevel.getDayTime(), TICKS_PER_DAY);
            savedData.recordDomesticExport(settlement, exportIndustryType(), removed, currentDay);
            savedData.setDirty();
            refreshGoggleState();
            return createExportStack(settlement, removed);
        }

        @Override
        public int getSlotLimit(int slot) {
            return slot == 0 ? 64 : 0;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot == 0 && operatingMode == OperatingMode.INTAKE && isAcceptedCommodity(stack);
        }
    }

    private enum OperatingMode {
        INTAKE("Intake"),
        EXPORT_BREAD("Export Bread"),
        EXPORT_BRICKS("Export Bricks");

        private final String displayName;

        OperatingMode(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }

        String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        OperatingMode next() {
            OperatingMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        static OperatingMode fromSerializedName(String name) {
            if (name == null || name.isBlank()) {
                return INTAKE;
            }
            for (OperatingMode mode : values()) {
                if (mode.serializedName().equals(name)) {
                    return mode;
                }
            }
            return INTAKE;
        }
    }
}
