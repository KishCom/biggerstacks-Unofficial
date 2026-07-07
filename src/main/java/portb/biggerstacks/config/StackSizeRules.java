/*
 * Copyright (c) PORTB 2025
 *
 * Licensed under GNU LGPL v3
 * https://www.gnu.org/licenses/lgpl-3.0.txt
 */

package portb.biggerstacks.config;

import portb.configlib.ItemProperties;
import portb.configlib.TagAccessor;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import portb.biggerstacks.BiggerStacks;
import portb.biggerstacks.util.ItemStackSizeHelper;
import portb.configlib.xml.RuleSet;

public class StackSizeRules
{
    private static final int ABSOLUTE_MAX_STACK_SIZE = 1_073_741_823;
    private static boolean clampWarningLogged = false;

    /**
     * Tracks the maximum stack size from registered item (see {@link portb.biggerstacks.mixin.vanilla.ItemPropertiesMixin#recordMaxRegisteredItemStackSize(int, CallbackInfoReturnable)} (int, CallbackInfoReturnable)})
     */
    public static int maxRegisteredItemStackSize = 99; // start at vanilla default so we don't clamp down before rules load
    
    /**
     * The global ruleset that is either received from the server or read from the config file
     */
    private static RuleSet ruleSet;
    private static int normalPreset     = 64;
    private static int potionPreset     = 1;
    private static int enchantedPreset  = 1;
    private static final TagAccessor EMPTY_TAG_ACCESSOR = tag -> false;
    
    public static RuleSet getRuleSet()
    {
        return ruleSet;
    }
    
    public static void setRuleSet(RuleSet ruleSet)
    {
        StackSizeRules.ruleSet = ruleSet;
        ItemStackSizeHelper.invalidateCache();

        if (ruleSet != null)
            BiggerStacks.LOGGER.info("Loaded BiggerStacks ruleset. Max declared stack size: {}", ruleSet.getMaxStacksize());
        else
            BiggerStacks.LOGGER.warn("Stack size ruleset reset to null; falling back to defaults.");

        recalculatePresets(ruleSet);
    }
    
    public static int getMaxStackSize()
    {
        int configuredMax = maxRegisteredItemStackSize;
        if (ruleSet != null)
            configuredMax = Math.max(ruleSet.getMaxStacksize(), maxRegisteredItemStackSize);
        
        return clampStackSize(configuredMax);
    }

    public static int getBaselineStackSize()
    {
        return Math.max(64, Math.min(maxRegisteredItemStackSize, ABSOLUTE_MAX_STACK_SIZE));
    }

    public static int clampStackSize(int requestedSize)
    {
        if (requestedSize > ABSOLUTE_MAX_STACK_SIZE)
        {
            if (!clampWarningLogged)
            {
                BiggerStacks.LOGGER.warn("Requested stack size {} exceeds hard cap of {}; larger values will be clamped.", requestedSize, ABSOLUTE_MAX_STACK_SIZE);
                clampWarningLogged = true;
            }
            return ABSOLUTE_MAX_STACK_SIZE;
        }
        return requestedSize;
    }

    public static int resolvePreset(TemplateOverrideConfig.StackCategory category)
    {
        return switch (category)
                {
                    case NORMAL -> normalPreset;
                    case POTION -> potionPreset;
                    case ENCHANTED_BOOK -> enchantedPreset;
                };
    }

    private static void recalculatePresets(RuleSet ruleSet)
    {
        if (ruleSet == null)
        {
            normalPreset = 64;
            potionPreset = 1;
            enchantedPreset = 1;
            return;
        }

        normalPreset = ruleSet.determineStackSizeForItem(
                        makeProperties("minecraft:stone", 64, false, true, false, false))
                .orElse(64);

        potionPreset = ruleSet.determineStackSizeForItem(
                        makeProperties("minecraft:potion", 1, true, false, false, false))
                .orElse(1);

        enchantedPreset = ruleSet.determineStackSizeForItem(
                        makeProperties("minecraft:enchanted_book", 1, false, false, false, false))
                .orElse(1);
    }

    private static ItemProperties makeProperties(String identifier, int stackSize, boolean edible, boolean placeable, boolean damageable, boolean bucket)
    {
        String namespace = identifier.contains(":") ? identifier.substring(0, identifier.indexOf(':')) : "minecraft";
        return new ItemProperties(
                namespace,
                identifier,
                stackSize,
                edible,
                placeable,
                damageable,
                bucket,
                EMPTY_TAG_ACCESSOR,
                Object.class
        );
    }
}
