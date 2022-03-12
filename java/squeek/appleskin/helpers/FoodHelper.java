package squeek.appleskin.helpers;

import com.mojang.datafixers.util.Pair;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Food;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.EffectInstance;
import net.minecraft.potion.EffectType;
import net.minecraft.potion.Effects;
import net.minecraft.util.FoodStats;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import squeek.appleskin.api.food.FoodValues;

public class FoodHelper
{
	public static boolean isFood(ItemStack itemStack)
	{
		return itemStack.getItem().getFood() != null;
	}

	public static boolean canConsume(ItemStack itemStack, PlayerEntity player)
	{
		// item is not a food that can be consume
		if (!isFood(itemStack))
			return false;

		Food itemFood = itemStack.getItem().getFood();
		if (itemFood == null)
			return false;

		return player.canEat(itemFood.canEatWhenFull());
	}

	public static FoodValues getDefaultFoodValues(ItemStack itemStack)
	{
		Food itemFood = itemStack.getItem().getFood();
		int hunger = itemFood != null ? itemFood.getHealing() : 0;
		float saturationModifier = itemFood != null ? itemFood.getSaturation() : 0;

		return new FoodValues(hunger, saturationModifier);
	}

	public static FoodValues getModifiedFoodValues(ItemStack itemStack, PlayerEntity player)
	{
		// Previously, this would use AppleCore to get the modified values, but since AppleCore doesn't
		// exist on this MC version and https://github.com/MinecraftForge/MinecraftForge/pull/7266
		// hasn't been merged, we just return the defaults here.
		return getDefaultFoodValues(itemStack);
	}

	public static boolean isRotten(ItemStack itemStack)
	{
		if (!isFood(itemStack))
			return false;

		for (Pair<EffectInstance, Float> effect : itemStack.getItem().getFood().getEffects())
		{
			if (effect.getFirst() != null && effect.getFirst().getPotion() != null && effect.getFirst().getPotion().getEffectType() == EffectType.HARMFUL)
			{
				return true;
			}
		}
		return false;
	}

	public static float getEstimatedHealthIncrement(ItemStack itemStack, FoodValues modifiedFoodValues, PlayerEntity player)
	{
		if (!isFood(itemStack))
			return 0;

		if (!player.shouldHeal())
			return 0;

		FoodStats stats = player.getFoodStats();
		World world = player.getEntityWorld();

		int foodLevel = Math.min(stats.getFoodLevel() + modifiedFoodValues.hunger, 20);
		float healthIncrement = 0;

		// health for natural regen
		if (foodLevel >= 18.0F && world != null && world.getGameRules().getBoolean(GameRules.NATURAL_REGENERATION))
		{
			float saturationLevel = Math.min(stats.getSaturationLevel() + modifiedFoodValues.getSaturationIncrement(), (float) foodLevel);
			float exhaustionLevel = HungerHelper.getExhaustion(player);
			healthIncrement = getEstimatedHealthIncrement(foodLevel, saturationLevel, exhaustionLevel);
		}

		// health for regeneration effect
		for (Pair<EffectInstance, Float> effect : itemStack.getItem().getFood().getEffects())
		{
			EffectInstance effectInstance = effect.getFirst();
			if (effectInstance != null && effectInstance.getPotion() == Effects.REGENERATION)
			{
				int amplifier = effectInstance.getAmplifier();
				int duration = effectInstance.getDuration();

				// Refer: https://minecraft.fandom.com/wiki/Regeneration
				// Refer: net.minecraft.world.effect.MobEffect.isDurationEffectTick
				healthIncrement += (float) Math.floor(duration / Math.max(50 >> amplifier, 1));
				break;
			}
		}

		return healthIncrement;
	}

	public static float REGEN_EXHAUSTION_INCREMENT = 6.0F;
	public static float MAX_EXHAUSTION = 4.0F;

	public static float getEstimatedHealthIncrement(int foodLevel, float saturationLevel, float exhaustionLevel)
	{
		float health = 0;

		if (!Float.isFinite(exhaustionLevel) || !Float.isFinite(saturationLevel))
			return 0;

		while (foodLevel >= 18)
		{
			while (exhaustionLevel > MAX_EXHAUSTION)
			{
				exhaustionLevel -= MAX_EXHAUSTION;
				if (saturationLevel > 0)
					saturationLevel = Math.max(saturationLevel - 1, 0);
				else
					foodLevel -= 1;
			}
			// Without this Float.compare, it's possible for this function to get stuck in an infinite loop
			// if saturationLevel is small enough that exhaustionLevel does not actually change representation
			// when it's incremented. This Float.compare makes it so we treat such close-to-zero values as zero.
			if (foodLevel >= 20 && Float.compare(saturationLevel, Float.MIN_NORMAL) > 0)
			{
				// fast regen health
				//
				// Because only health and exhaustionLevel increase in this branch,
				// we know that we will enter this branch again and again on each iteration
				// if exhaustionLevel is not incremented above MAX_EXHAUSTION before the
				// next iteration.
				//
				// So, instead of actually performing those iterations, we can calculate
				// the number of iterations it would take to reach max exhaustion, and
				// add all the health/exhaustion in one go. In practice, this takes the
				// worst-case number of iterations performed in this function from the millions
				// all the way down to around 18.
				//
				// Note: Due to how floating point works, the results of actually doing the
				// iterations and 'simulating' them using multiplication will differ. That is, small increments
				// in a loop can end up with a different (and higher) final result than multiplication
				// due to floating point rounding. In degenerate cases, the difference can be fairly high
				// (when testing, I found a case that had a difference of ~0.3), but this isn't a concern in
				// this particular instance because the 'real' difference as seen by the player
				// would likely take hundreds of thousands of ticks to materialize (since the
				// `limitedSaturationLevel / REGEN_EXHAUSTION_INCREMENT` value must be very
				// small for a difference to occur at all, and therefore numIterationsUntilAboveMax would
				// be very large).
				float limitedSaturationLevel = Math.min(saturationLevel, REGEN_EXHAUSTION_INCREMENT);
				float exhaustionUntilAboveMax = Math.nextUp(MAX_EXHAUSTION) - exhaustionLevel;
				int numIterationsUntilAboveMax = Math.max(1, (int) Math.ceil(exhaustionUntilAboveMax / limitedSaturationLevel));

				health += (limitedSaturationLevel / REGEN_EXHAUSTION_INCREMENT) * numIterationsUntilAboveMax;
				exhaustionLevel += limitedSaturationLevel * numIterationsUntilAboveMax;
			}
			else if (foodLevel >= 18)
			{
				// slow regen health
				health += 1;
				exhaustionLevel += REGEN_EXHAUSTION_INCREMENT;
			}
		}

		return health;
	}
}
