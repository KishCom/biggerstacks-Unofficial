/*
 * Copyright (c) PORTB 2025
 *
 * Licensed under GNU LGPL v3
 * https://www.gnu.org/licenses/lgpl-3.0.txt
 */

package portb.biggerstacks.mixin.compat.sophisticatedcore;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.p3pp3rf1y.sophisticatedcore.inventory.IItemHandlerSimpleInserter;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;
import net.p3pp3rf1y.sophisticatedcore.upgrades.FilterLogic;
import net.p3pp3rf1y.sophisticatedcore.upgrades.compacting.CompactingUpgradeConfig;
import net.p3pp3rf1y.sophisticatedcore.upgrades.compacting.CompactingUpgradeItem;
import net.p3pp3rf1y.sophisticatedcore.upgrades.compacting.CompactingUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.util.InventoryHelper;
import net.p3pp3rf1y.sophisticatedcore.util.RecipeHelper;
import net.p3pp3rf1y.sophisticatedcore.util.RecipeHelper.CompactingResult;
import net.p3pp3rf1y.sophisticatedcore.util.RecipeHelper.CompactingShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Pseudo
@Mixin(value = CompactingUpgradeWrapper.class, remap = false)
public abstract class CompactingUpgradeWrapperMixin
{
    private static final int MAX_ITEMS_PER_COMPACT_BATCH = 64 * 1024;

    @Shadow
    protected abstract boolean fitsResultAndRemainingItems(IItemHandler inventoryHandler, List<ItemStack> remainingItems, ItemStack result);

    @Shadow
    public abstract boolean shouldCompactNonUncraftable();

    @Shadow
    public abstract FilterLogic getFilterLogic();

    @Shadow
    private Map<Item, Integer> fullSlotsToCompactLater;

    /**
     * Replaces vanilla compacting with a version that caps how many items are compacted per call.
     * Vanilla loops until the input runs out, which causes huge lag spikes with the stack sizes this mod allows.
     */
    @Inject(method = "compactSlot", at = @At("HEAD"), cancellable = true)
    private void biggerstacks$batchCompactSlot(IItemHandlerSimpleInserter inventoryHandler, int slot, CallbackInfo ci)
    {
        ci.cancel();

        ItemStack slotStack = inventoryHandler.getStackInSlot(slot);

        if (slotStack.isEmpty() || !getFilterLogic().matchesFilter(slotStack))
            return;

        Optional<CompactingUpgradeConfig.CompactingDefinition> compactingDefinition = biggerstacks$getCompactingDefinition(
                slotStack);

        if (compactingDefinition.isEmpty())
            return;

        int             totalCount       = compactingDefinition.get().count();
        CompactingResult compactingResult = compactingDefinition.get().result();

        if (compactingResult.getResult().isEmpty())
            return;

        ItemStack extractedStack = InventoryHelper.extractFromInventory(slotStack.copyWithCount(totalCount), inventoryHandler, true);
        if (extractedStack.getCount() != totalCount)
            return;

        int maxConversionsPerCall = Math.max(1, MAX_ITEMS_PER_COMPACT_BATCH / totalCount);
        int conversions           = 0;

        while (extractedStack.getCount() == totalCount && conversions < maxConversionsPerCall)
        {
            ItemStack resultCopy = compactingResult.getResult().copy();
            List<ItemStack> remainingItemsCopy = compactingResult.getRemainingItems().isEmpty()
                    ? Collections.emptyList()
                    : compactingResult.getRemainingItems().stream().map(ItemStack::copy).toList();

            if (!fitsResultAndRemainingItems(inventoryHandler, remainingItemsCopy, resultCopy))
            {
                //remember full slots so compacting is retried when items are extracted, same as vanilla
                if (inventoryHandler instanceof InventoryHandler handler
                        && handler.getStackInSlot(slot).getCount() >= handler.getStackLimit(slot, slotStack))
                    fullSlotsToCompactLater.put(resultCopy.getItem(), slot);
                break;
            }

            fullSlotsToCompactLater.remove(resultCopy.getItem());
            InventoryHelper.extractFromInventory(slotStack.copyWithCount(totalCount), inventoryHandler, false);
            inventoryHandler.insertItem(resultCopy, false);
            InventoryHelper.insertIntoInventory(remainingItemsCopy, inventoryHandler, false);
            extractedStack = InventoryHelper.extractFromInventory(slotStack.copyWithCount(totalCount), inventoryHandler, true);
            conversions++;
        }
    }

    /**
     * Mirrors {@code CompactingUpgradeWrapper.getCompactingDefinition}, which can no longer be called directly
     * because its return type is package private.
     */
    @Unique
    private Optional<CompactingUpgradeConfig.CompactingDefinition> biggerstacks$getCompactingDefinition(ItemStack stack)
    {
        CompactingUpgradeItem compactingUpgradeItem = (CompactingUpgradeItem) ((UpgradeWrapperBaseAccessor) this).biggerstacks$getUpgradeItem();
        Set<CompactingShape>  shapes                = RecipeHelper.getItemCompactingShapes(stack);

        if (compactingUpgradeItem.shouldCompactThreeByThree() && (shapes.contains(CompactingShape.THREE_BY_THREE_UNCRAFTABLE)
                || (shouldCompactNonUncraftable() && shapes.contains(CompactingShape.THREE_BY_THREE))))
            return biggerstacks$getVanillaCompactingDefinition(stack, 3, 3);
        else if (shapes.contains(CompactingShape.TWO_BY_TWO_UNCRAFTABLE)
                || (shouldCompactNonUncraftable() && shapes.contains(CompactingShape.TWO_BY_TWO)))
            return biggerstacks$getVanillaCompactingDefinition(stack, 2, 2);

        int maxShapeSize = compactingUpgradeItem.shouldCompactThreeByThree() ? 3 : 2;
        return compactingUpgradeItem.getConfiguredCompactingResult(stack, maxShapeSize, maxShapeSize);
    }

    @Unique
    private Optional<CompactingUpgradeConfig.CompactingDefinition> biggerstacks$getVanillaCompactingDefinition(ItemStack stack, int width, int height)
    {
        CompactingResult compactingResult = RecipeHelper.getCompactingResult(stack, width, height);
        return compactingResult.getResult().isEmpty()
                ? Optional.empty()
                : Optional.of(new CompactingUpgradeConfig.CompactingDefinition(compactingResult, width * height));
    }
}
