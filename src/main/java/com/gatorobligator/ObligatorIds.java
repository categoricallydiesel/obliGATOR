package com.gatorobligator;

import java.util.HashSet;
import java.util.Set;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.SpotanimID;

final class ObligatorIds
{
	// Hues (HSL bits 10-15) of the thrust spotanim's parts, measured in game.
	// Saturation and luminance vary with lighting, so only hue is reliable.
	private static final int BALL_HUE = 21;
	private static final int GEM_CORE_HUE = 13;
	// the olive spike the chain attaches to, on top of the ball
	static final int SPIKE_HUE = 6;
	// spike vertices further than this from the ball's centre belong to other parts of the mace
	private static final float SPIKE_MAX_DISTANCE = 60;

	// Model of the charged thrust spotanims. Spotanim definitions are not in the API, so this was found by
	// scanning models for the spotanim's vertex and face counts (717 / 1212).
	static final int THRUST_MODEL = 62097;

	private ObligatorIds()
	{
	}

	static boolean isBallHue(int hue)
	{
		return hue == BALL_HUE || hue == GEM_CORE_HUE;
	}

	/**
	 * Vertices of the given spike hued faces that are close to the ball, which together with the ball's centre give
	 * the direction of the chain. The ball moves rigidly, so these can be found once and reused every frame.
	 */
	static int[] spikeVertices(float[] x, float[] y, float[] z, int[] f1, int[] f2, int[] f3, int[] spikeFaces,
		int[] ballVertices)
	{
		if (ballVertices.length == 0)
		{
			return new int[0];
		}

		float bx = 0, by = 0, bz = 0;
		for (int v : ballVertices)
		{
			bx += x[v];
			by += y[v];
			bz += z[v];
		}
		bx /= ballVertices.length;
		by /= ballVertices.length;
		bz /= ballVertices.length;

		Set<Integer> spike = new HashSet<>();
		for (int face : spikeFaces)
		{
			for (int v : new int[]{f1[face], f2[face], f3[face]})
			{
				float dx = x[v] - bx, dy = y[v] - by, dz = z[v] - bz;
				if (dx * dx + dy * dy + dz * dz <= SPIKE_MAX_DISTANCE * SPIKE_MAX_DISTANCE)
				{
					spike.add(v);
				}
			}
		}
		return spike.stream().mapToInt(Integer::intValue).toArray();
	}

	/**
	 * The 5-tick throw. The mace that flies out is drawn by a thrust spotanim, not by this animation.
	 */
	static boolean isThrowAnimation(int animId)
	{
		return animId == AnimationID.TFA_HUMAN_OBLIGATOR_MULTIHIT
			|| animId == AnimationID.TFA_HUMAN_OBLIGATOR_MULTIHIT_FEMALE;
	}

	/**
	 * The animation a thrust spotanim plays, inferred from the matching gameval names, or -1 if unknown.
	 */
	static int thrustAnimation(int spotanimId)
	{
		switch (spotanimId)
		{
			case SpotanimID.VFX_OBLIGATOR_CHARGED_THRUST_MALE:
				return AnimationID.VFX_OBLIGATOR_CHARGED_THRUST_MALE;
			case SpotanimID.VFX_OBLIGATOR_CHARGED_THRUST_FEMALE:
				return AnimationID.VFX_OBLIGATOR_CHARGED_THRUST_FEMALE;
			default:
				return -1;
		}
	}

	static boolean isThrustSpotanim(int spotanimId)
	{
		return spotanimId == SpotanimID.VFX_OBLIGATOR_CHARGED_THRUST_MALE
			|| spotanimId == SpotanimID.VFX_OBLIGATOR_UNCHARGED_THRUST_MALE
			|| spotanimId == SpotanimID.VFX_OBLIGATOR_CHARGED_THRUST_FEMALE
			|| spotanimId == SpotanimID.VFX_OBLIGATOR_UNCHARGED_THRUST_FEMALE;
	}
}
