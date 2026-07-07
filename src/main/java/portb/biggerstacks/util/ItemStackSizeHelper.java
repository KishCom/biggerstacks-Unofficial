/*
 * Copyright (c) PORTB 2025
 *
 * Licensed under GNU LGPL v3
 * https://www.gnu.org/licenses/lgpl-3.0.txt
 */

package portb.biggerstacks.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import portb.biggerstacks.Constants;
import portb.biggerstacks.config.StackSizeRules;
import portb.biggerstacks.config.TemplateOverrideConfig;
import portb.configlib.ItemProperties;
import portb.configlib.TagAccessor;

import java.util.concurrent.ConcurrentHashMap;

import static portb.biggerstacks.BiggerStacks.LOGGER;

public class ItemStackSizeHelper
{
    private static final ConcurrentHashMap<Item, Integer> STACK_SIZE_CACHE = new ConcurrentHashMap<>();
    private static boolean loggedApplyCall = false;

    public static void invalidateCache()
    {
        STACK_SIZE_CACHE.clear();
    }

    public static void applyStackSizeToItem(ItemStack itemstack, CallbackInfoReturnable<Integer> returnInfo)
    {
        var item = itemstack.getItem();
        var cached = STACK_SIZE_CACHE.get(item);
        if (cached != null)
        {
            returnInfo.cancel();
            returnInfo.setReturnValue(cached);
            return;
        }

        var overrideSize = TemplateOverrideConfig.resolveOverride(itemstack);
        if (overrideSize.isPresent())
        {
            int clampedStackSize = StackSizeRules.clampStackSize(overrideSize.getAsInt());
            STACK_SIZE_CACHE.put(item, clampedStackSize);
            returnInfo.cancel();
            returnInfo.setReturnValue(clampedStackSize);
            return;
        }

        if (StackSizeRules.getRuleSet() != null)
        {
            var itemKey = BuiltInRegistries.ITEM.getKey(item);
            var namespace = itemKey != null ? itemKey.getNamespace() : Constants.MOD_ID;
            var identifier = itemKey != null ? itemKey.toString() : item.getClass().getName();
            boolean isEdible          = item.components().has(DataComponents.FOOD);
            boolean isBlock          = item instanceof BlockItem;
            boolean canBeDepleted    = itemstack.isDamageableItem();
            boolean isBucket         = item instanceof BucketItem;
            int      currentMaxStack = returnInfo.getReturnValue();
            
            var result = StackSizeRules.getRuleSet().determineStackSizeForItem(
                                  new ItemProperties(
                                          namespace,
                                          identifier,
                                          currentMaxStack,
                                          isEdible,
                                          isBlock,
                                          canBeDepleted,
                                          isBucket,
                                          new TagAccessorImpl(itemstack),
                                          item.getClass()
                                  )
                          );
            if (result.isPresent())
            {
                int clampedStackSize = StackSizeRules.clampStackSize(result.get());
                if (!loggedApplyCall)
                {
                    loggedApplyCall = true;
                    LOGGER.info("ItemStackSizeHelper invoked; sample item {} -> {}", identifier, clampedStackSize);
                }
                STACK_SIZE_CACHE.put(item, clampedStackSize);
                returnInfo.cancel();
                returnInfo.setReturnValue(clampedStackSize);
            }
            else
            {
                STACK_SIZE_CACHE.put(item, currentMaxStack);
            }
        }
        else
        {
            LOGGER.debug("Stack size ruleset is somehow null, using fallback logic. Called from " +
                                 CallingClassUtil.getCallerClassName());

            if (returnInfo.getReturnValue() > 1)
            {
                int newSize = StackSizeHelper.getNewStackSize();
                STACK_SIZE_CACHE.put(item, newSize);
                returnInfo.cancel();
                returnInfo.setReturnValue(newSize);
            }
            else
            {
                STACK_SIZE_CACHE.put(item, returnInfo.getReturnValue());
            }
        }
    }
    
    private record TagAccessorImpl(ItemStack item) implements TagAccessor
    {
        @Override
        public boolean doesItemHaveTag(String tag)
        {
            ResourceLocation tagId = ResourceLocation.parse(tag);
            return item.is(TagKey.create(Registries.ITEM, tagId));
        }
    }
}
