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
            if (slot != 0 || stack.isEmpty() || !stack.is(Items.BREAD)) {
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

            int capacity = Math.max(0, settlement.getBreadTarget() - settlement.getBreadSupplied());
            int accepted = Math.min(capacity, stack.getCount());
            if (accepted <= 0) {
                return stack;
            }

            if (!simulate) {
                accepted = settlement.deliverBread(accepted);
                if (accepted > 0) {
                    savedData.setDirty();
                }
            }

            ItemStack remainder = stack.copy();
            remainder.shrink(accepted);
            return remainder;
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
            return slot == 0 && stack.is(Items.BREAD);
        }
    }
}
