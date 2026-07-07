/*
 * Copyright (c) PORTB 2025
 *
 * Licensed under GNU LGPL v3
 * https://www.gnu.org/licenses/lgpl-3.0.txt
 */

package portb.biggerstacks.mixin.vanilla;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.util.ExtraCodecs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import portb.biggerstacks.BiggerStacks;

@Mixin(ExtraCodecs.class)
public class ExtraCodecsMixin
{
    private static boolean biggerstacks$loggedCodecPatch = false;

    @Inject(method = "intRange", at = @At("HEAD"), cancellable = true)
    private static void biggerstacks$expandStackCountRange(int min, int max, CallbackInfoReturnable<Codec<Integer>> returnInfo)
    {
        if (min == 1 && max == 99 && isBuildingItemStackCodec())
        {
            int patchedMax = Integer.MAX_VALUE;
            if (!biggerstacks$loggedCodecPatch)
            {
                biggerstacks$loggedCodecPatch = true;
                BiggerStacks.LOGGER.info("Expanding ItemStack count codec limit from {} to {}", max, patchedMax);
            }
            returnInfo.setReturnValue(createRangeCodec(min, patchedMax));
            returnInfo.cancel();
        }
    }

    private static Codec<Integer> createRangeCodec(int min, int max)
    {
        return Codec.INT.validate((value) -> value >= min && value <= max
                ? DataResult.success(value)
                : DataResult.error(() -> "Value must be within range [" + min + ";" + max + "]: " + value));
    }

    private static boolean isBuildingItemStackCodec()
    {
        return StackWalker.getInstance().walk(frames ->
                frames.limit(5).anyMatch(frame ->
                        "net.minecraft.world.item.ItemStack".equals(frame.getClassName())));
    }
}
