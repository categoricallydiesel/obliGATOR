package com.gatorobligator;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.api.kit.KitType;

/**
 * Developer-mode helper that makes the local player look like they are wielding the Obligator
 * (client-side only) so the plugin can be tested without owning the weapon, and tunes the gator head live.
 */
@Singleton
class ObligatorPreview
{
	// spotanim slot used for preview graphics, kept away from slot 0 which the server uses
	private static final int PREVIEW_SPOTANIM_KEY = 7;

	@Inject
	private Client client;

	@Inject
	private GatorModel gatorModel;

	private boolean enabled;
	private int originalWeapon = -1;

	void handleCommand(String[] args)
	{
		Player player = client.getLocalPlayer();
		if (player == null || player.getPlayerComposition() == null)
		{
			return;
		}

		if (args.length == 0)
		{
			if (enabled)
			{
				disable();
				message("Obligator preview off");
			}
			else
			{
				enabled = true;
				apply();
				message("Obligator preview on. Try ::oblig attack or ::oblig multi");
			}
			return;
		}

		if (handleTuning(args))
		{
			return;
		}

		boolean female = player.getPlayerComposition().isFemale();
		switch (args[0].toLowerCase())
		{
			case "attack":
				player.setAnimation(female ? AnimationID.TFA_HUMAN_OBLIGATOR_ATTACK_FEMALE : AnimationID.TFA_HUMAN_OBLIGATOR_ATTACK);
				player.setAnimationFrame(0);
				break;
			case "multi":
				player.setAnimation(female ? AnimationID.TFA_HUMAN_OBLIGATOR_MULTIHIT_FEMALE : AnimationID.TFA_HUMAN_OBLIGATOR_MULTIHIT);
				player.setAnimationFrame(0);
				// the thrown mace is a separate spotanim, not part of the player animation
				player.createSpotAnim(PREVIEW_SPOTANIM_KEY,
					female ? SpotanimID.VFX_OBLIGATOR_CHARGED_THRUST_FEMALE : SpotanimID.VFX_OBLIGATOR_CHARGED_THRUST_MALE, 0, 0);
				break;
			default:
				message("Usage: ::oblig [attack|multi|scale n|cut z|cuty y|bite animId|spirit full/head|spiritscale n"
					+ "|spiritalpha 0-255|spiritpos n|chomp cycle]");
		}
	}

	/**
	 * Commands for tuning the gator head live, returning true if one was handled.
	 */
	private boolean handleTuning(String[] args)
	{
		String cmd = args[0].toLowerCase();
		if (cmd.equals("spirit"))
		{
			if (args.length > 1)
			{
				gatorModel.setSpiritFull(!args[1].equalsIgnoreCase("head"));
				gatorModel.invalidate();
			}
			message("Spirit: " + (gatorModel.isSpiritFull() ? "full" : "head"));
			return true;
		}

		if (args.length < 2 || !(cmd.equals("scale") || cmd.equals("cut") || cmd.equals("cuty") || cmd.equals("bite")
			|| cmd.equals("spiritscale") || cmd.equals("spiritalpha") || cmd.equals("spiritpos") || cmd.equals("chomp")))
		{
			return false;
		}

		int value;
		try
		{
			value = args[1].equalsIgnoreCase("off") ? Integer.MAX_VALUE : Integer.parseInt(args[1]);
		}
		catch (NumberFormatException e)
		{
			message("Not a number: " + args[1]);
			return true;
		}

		switch (cmd)
		{
			case "scale":
				gatorModel.setScale(value);
				break;
			case "cut":
				gatorModel.setCutZ(value);
				break;
			case "cuty":
				gatorModel.setCutY(value);
				break;
			case "bite":
				gatorModel.setBiteAnimId(value);
				break;
			case "spiritscale":
				gatorModel.setSpiritScale(value);
				break;
			case "spiritalpha":
				gatorModel.setSpiritAlpha(value);
				break;
			case "spiritpos":
				gatorModel.setSpiritPosition(value);
				break;
			case "chomp":
				gatorModel.setChompCycle(value);
				break;
		}
		gatorModel.invalidate();
		message(String.format("Gator: scale=%d cut=%s cuty=%s bite=%d spiritscale=%d spiritalpha=%d spiritpos=%d chomp=%d",
			gatorModel.getScale(),
			gatorModel.getCutZ() == Integer.MAX_VALUE ? "off" : gatorModel.getCutZ(),
			gatorModel.getCutY() == Integer.MAX_VALUE ? "off" : gatorModel.getCutY(),
			gatorModel.getBiteAnimId(), gatorModel.getSpiritScale(), gatorModel.getSpiritAlpha(),
			gatorModel.getSpiritPosition(), gatorModel.getChompCycle()));
		return true;
	}

	/**
	 * Re-apply after the server sends a fresh appearance, which would otherwise undo the preview.
	 */
	void onPlayerChanged(Player player)
	{
		if (enabled && player == client.getLocalPlayer())
		{
			apply();
		}
	}

	void disable()
	{
		if (!enabled)
		{
			return;
		}
		enabled = false;

		Player player = client.getLocalPlayer();
		PlayerComposition comp = player == null ? null : player.getPlayerComposition();
		if (comp != null && originalWeapon != -1)
		{
			comp.getEquipmentIds()[KitType.WEAPON.getIndex()] = originalWeapon;
			comp.setHash();
		}
		originalWeapon = -1;
	}

	private void apply()
	{
		Player player = client.getLocalPlayer();
		PlayerComposition comp = player == null ? null : player.getPlayerComposition();
		if (comp == null)
		{
			return;
		}

		int[] equipment = comp.getEquipmentIds();
		int obligator = ItemID.OBLIGATOR + PlayerComposition.ITEM_OFFSET;
		if (equipment[KitType.WEAPON.getIndex()] != obligator)
		{
			originalWeapon = equipment[KitType.WEAPON.getIndex()];
			equipment[KitType.WEAPON.getIndex()] = obligator;
			comp.setHash();
		}

		boolean female = comp.isFemale();
		player.setIdlePoseAnimation(female ? AnimationID.TFA_HUMAN_OBLIGATOR_READY_FEMALE : AnimationID.TFA_HUMAN_OBLIGATOR_READY);
		player.setWalkAnimation(female ? AnimationID.TFA_HUMAN_OBLIGATOR_WALK_F_FEMALE : AnimationID.TFA_HUMAN_OBLIGATOR_WALK_F);
		player.setRunAnimation(female ? AnimationID.TFA_HUMAN_OBLIGATOR_RUN_F_FEMALE : AnimationID.TFA_HUMAN_OBLIGATOR_RUN_F);

		// the turn animations of the real weapon don't fit the Obligator's skeleton and stretch its chain. There are
		// no Obligator turn animations in the gamevals, so use the walks, which is what the server likely sends
		int walk = female ? AnimationID.TFA_HUMAN_OBLIGATOR_WALK_F_FEMALE : AnimationID.TFA_HUMAN_OBLIGATOR_WALK_F;
		int walkBack = female ? AnimationID.TFA_HUMAN_OBLIGATOR_WALK_B_FEMALE : AnimationID.TFA_HUMAN_OBLIGATOR_WALK_B;
		player.setIdleRotateLeft(walk);
		player.setIdleRotateRight(walk);
		player.setWalkRotateLeft(walk);
		player.setWalkRotateRight(walk);
		player.setWalkRotate180(walkBack);
	}

	private void message(String text)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", text, null);
	}
}
