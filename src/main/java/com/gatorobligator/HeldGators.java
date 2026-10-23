package com.gatorobligator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.kit.KitType;

/**
 * Replaces the ball of a wielded Obligator with a static Zebak head.
 *
 * The model returned by Player.getModel() shares its face colors with the appearance model the client caches and
 * draws, so hiding the ball faces there persists. The original colors are kept and put back when the player stops
 * wielding the Obligator or the plugin stops.
 */
@Slf4j
@Singleton
class HeldGators
{
	// ball hued faces further apart than this, by face index, belong to different equipment
	private static final int MAX_FACE_GAP = 64;
	// smallest group of ball hued faces taken to be the ball; the Obligator's ball has 544
	private static final int MIN_BALL_FACES = 400;

	@Inject
	private Client client;

	@Inject
	private GatorModel gatorModel;

	private final Map<Player, Held> heldByPlayer = new HashMap<>();
	// face color arrays of cached player models with the ball hidden, and their original values
	private final Map<int[], Hidden> hidden = new IdentityHashMap<>();

	void onPlayerChanged(Player player)
	{
		if (!isWielding(player))
		{
			remove(player);
		}
		else if (!heldByPlayer.containsKey(player))
		{
			heldByPlayer.put(player, new Held());
		}
	}

	void onPlayerDespawned(Player player)
	{
		remove(player);
	}

	/**
	 * Picks up players that were already wielding the Obligator when the plugin started.
	 */
	void scanPlayers()
	{
		for (Player player : client.getTopLevelWorldView().players())
		{
			onPlayerChanged(player);
		}
	}

	void onBeforeRender()
	{
		Player local = client.getLocalPlayer();
		if (local != null && !heldByPlayer.containsKey(local) && isWielding(local))
		{
			// equipment changed client side, such as by the preview, does not fire PlayerChanged
			heldByPlayer.put(local, new Held());
		}

		Iterator<Map.Entry<Player, Held>> it = heldByPlayer.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<Player, Held> e = it.next();
			Player player = e.getKey();
			Held held = e.getValue();
			if (!isWielding(player))
			{
				held.despawn();
				restore(held);
				it.remove();
				continue;
			}
			update(player, held);
		}
	}

	void clear()
	{
		for (Held held : heldByPlayer.values())
		{
			held.despawn();
		}
		heldByPlayer.clear();
		for (Map.Entry<int[], Hidden> e : hidden.entrySet())
		{
			e.getValue().restore(e.getKey());
		}
		hidden.clear();
	}

	private void update(Player player, Held held)
	{
		Model model = player.getModel();
		LocalPoint lp = player.getLocalLocation();
		if (model == null || lp == null || model.getFaceColors3() == null)
		{
			held.hideHead();
			return;
		}

		int[] colors3 = model.getFaceColors3();
		if (colors3 != held.colors3)
		{
			// a new appearance model, such as after changing gear, so find its ball again
			held.colors3 = colors3;
			held.ballFaces = findBall(model);
			held.ballVertices = vertices(model, held.ballFaces);
			held.spikeVertices = findSpike(model, held.ballFaces, held.ballVertices);
		}
		if (held.ballFaces.length == 0 || held.spikeVertices.length == 0)
		{
			held.hideHead();
			return;
		}

		hide(held, colors3);

		float[] x = model.getVerticesX();
		float[] y = model.getVerticesY();
		float[] z = model.getVerticesZ();
		float bx = 0, by = 0, bz = 0;
		for (int v : held.ballVertices)
		{
			bx += x[v];
			by += y[v];
			bz += z[v];
		}
		bx /= held.ballVertices.length;
		by /= held.ballVertices.length;
		bz /= held.ballVertices.length;

		float sx = 0, sy = 0, sz = 0;
		for (int v : held.spikeVertices)
		{
			sx += x[v];
			sy += y[v];
			sz += z[v];
		}
		sx /= held.spikeVertices.length;
		sy /= held.spikeVertices.length;
		sz /= held.spikeVertices.length;

		if (held.head == null || held.head.version != gatorModel.getVersion())
		{
			held.despawn();
			held.head = gatorModel.createHead();
			if (held.head == null)
			{
				return;
			}
			held.object = client.createRuneLiteObject();
			held.object.setModel(held.head.model);
		}

		// the neck sits on the spike the chain attaches to, with the snout pointing away from the chain. The head
		// keeps the orientation it has while standing, as the swaying of walks and swings would make it flip about
		if (held.basis == null || isIdle(player))
		{
			held.basis = GatorModel.uprightBasis(bx - sx, by - sy, bz - sz);
		}
		gatorModel.pose(held.head, null, -1, gatorModel.headScale(), held.basis, GatorModel.Pivot.NECK);

		// the spike is in the player model's space
		int plane = player.getWorldView().getPlane();
		GatorModel.place(held.object, lp, plane, player.getCurrentOrientation(),
			Perspective.getTileHeight(client, lp, plane), sx, sy, sz);
		if (!held.object.isActive())
		{
			held.object.setActive(true);
		}
	}

	private void hide(Held held, int[] colors3)
	{
		Hidden h = hidden.get(colors3);
		if (h == null)
		{
			h = new Hidden(held.ballFaces, colors3);
			hidden.put(colors3, h);
			held.hiddenArrays.add(colors3);
		}
		// the client can relight a cached model, so keep the ball hidden every frame
		for (int face : held.ballFaces)
		{
			colors3[face] = -2;
		}
	}

	private void restore(Held held)
	{
		for (int[] colors3 : held.hiddenArrays)
		{
			Hidden h = hidden.remove(colors3);
			if (h != null)
			{
				h.restore(colors3);
			}
		}
		held.hiddenArrays.clear();
	}

	private void remove(Player player)
	{
		Held held = heldByPlayer.remove(player);
		if (held != null)
		{
			held.despawn();
			restore(held);
		}
	}

	/**
	 * Finds the ball as the largest run of ball hued faces that are close together by index. Merged player models
	 * keep each equipment piece's faces together, so ball hues on other gear form separate runs.
	 */
	private static int[] findBall(Model model)
	{
		int[] colors = model.getFaceColors1();
		List<Integer> faces = new ArrayList<>();
		for (int i = 0; i < model.getFaceCount(); i++)
		{
			if (ObligatorIds.isBallHue((colors[i] >> 10) & 63))
			{
				faces.add(i);
			}
		}

		int bestStart = 0, bestLength = 0;
		int start = 0;
		for (int i = 1; i <= faces.size(); i++)
		{
			if (i == faces.size() || faces.get(i) - faces.get(i - 1) > MAX_FACE_GAP)
			{
				if (i - start > bestLength)
				{
					bestStart = start;
					bestLength = i - start;
				}
				start = i;
			}
		}

		if (bestLength < MIN_BALL_FACES)
		{
			log.debug("No Obligator ball found in player model ({} ball hued faces, largest run {})", faces.size(), bestLength);
			return new int[0];
		}
		log.debug("Found Obligator ball: {} faces from {} to {}", bestLength, faces.get(bestStart),
			faces.get(bestStart + bestLength - 1));
		return faces.subList(bestStart, bestStart + bestLength).stream().mapToInt(Integer::intValue).toArray();
	}

	/**
	 * Spike vertices next to the ball, only looking at faces near the ball's faces so other gear is ignored.
	 */
	private static int[] findSpike(Model model, int[] ballFaces, int[] ballVertices)
	{
		if (ballFaces.length == 0)
		{
			return new int[0];
		}

		int[] colors = model.getFaceColors1();
		int first = Math.max(0, ballFaces[0] - MAX_FACE_GAP);
		int last = Math.min(model.getFaceCount() - 1, ballFaces[ballFaces.length - 1] + MAX_FACE_GAP);
		List<Integer> spikeFaces = new ArrayList<>();
		for (int i = first; i <= last; i++)
		{
			if (((colors[i] >> 10) & 63) == ObligatorIds.SPIKE_HUE)
			{
				spikeFaces.add(i);
			}
		}

		int[] spike = ObligatorIds.spikeVertices(model.getVerticesX(), model.getVerticesY(), model.getVerticesZ(),
			model.getFaceIndices1(), model.getFaceIndices2(), model.getFaceIndices3(),
			spikeFaces.stream().mapToInt(Integer::intValue).toArray(), ballVertices);
		log.debug("Found Obligator spike: {} vertices from {} spike hued faces", spike.length, spikeFaces.size());
		return spike;
	}

	private static int[] vertices(Model model, int[] faces)
	{
		int[] f1 = model.getFaceIndices1();
		int[] f2 = model.getFaceIndices2();
		int[] f3 = model.getFaceIndices3();
		return Arrays.stream(faces)
			.flatMap(f -> Arrays.stream(new int[]{f1[f], f2[f], f3[f]}))
			.distinct()
			.toArray();
	}

	private static boolean isIdle(Player player)
	{
		return player.getAnimation() == -1 && player.getPoseAnimation() == player.getIdlePoseAnimation();
	}

	private static boolean isWielding(Player player)
	{
		PlayerComposition comp = player.getPlayerComposition();
		if (comp == null)
		{
			return false;
		}
		int weapon = comp.getEquipmentId(KitType.WEAPON);
		// the uncharged Obligator is left as the game draws it
		return weapon == ItemID.OBLIGATOR;
	}

	private static final class Held
	{
		int[] colors3;
		int[] ballFaces = new int[0];
		int[] ballVertices = new int[0];
		int[] spikeVertices = new int[0];
		// orientation of the head while standing, kept while moving
		float[] basis;
		GatorModel.Head head;
		RuneLiteObject object;
		final List<int[]> hiddenArrays = new ArrayList<>();

		void hideHead()
		{
			if (object != null && object.isActive())
			{
				object.setActive(false);
			}
		}

		void despawn()
		{
			hideHead();
			object = null;
			head = null;
		}
	}

	private static final class Hidden
	{
		final int[] faces;
		final int[] saved;

		Hidden(int[] faces, int[] colors3)
		{
			this.faces = faces;
			this.saved = new int[faces.length];
			for (int i = 0; i < faces.length; i++)
			{
				saved[i] = colors3[faces[i]];
			}
		}

		void restore(int[] colors3)
		{
			for (int i = 0; i < faces.length; i++)
			{
				colors3[faces[i]] = saved[i];
			}
		}
	}
}
