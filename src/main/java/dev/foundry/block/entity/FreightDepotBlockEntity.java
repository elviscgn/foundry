package dev.foundry.block.entity;

import dev.foundry.registry.ModBlockEntities;
import dev.foundry.settlement.Settlement;
import dev.foundry.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

public final class FreightDepotBlockEntity extends BlockEntity {
    private final IItemHandler depotItemHandler = new DepotItemHandler();
    private LazyOptional<IItemHandler> itemHandlerCapability = LazyOptional.of(() -> depotItemHandler);

    public FreightDepotBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FREIGHT_DEPOT.get(), pos, state);
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

    private final class DepotItemHandler implements IItemHandler {
        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot != 0 || stack.isEmpty() || !isAcceptedCommodity(stack)) {
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
                    savedData.setDirty();
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
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return slot == 0 ? 64 : 0;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot == 0 && isAcceptedCommodity(stack);
        }
    }
}
