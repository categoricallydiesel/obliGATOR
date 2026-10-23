package com.gatorobligator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Animation;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;

/**
 * Replaces the Obligator's thrust spotanim with a copy whose ball is a biting Zebak head.
 *
 * Actor spotanims are merged into the actor's model when drawn, so their faces cannot be hidden in place.
 * Instead the spotanim is removed (client side only) and redrawn as a RuneLiteObject from the same model and
 * animation, with the ball faces hidden and the head placed where the ball is.
 */
@Slf4j
@Singleton
class GatorThrows
{
	// the ball grows from a point as it is thrown, the head is shown once it is this fraction of full size
	private static final float MIN_VISIBLE_SIZE = 0.05f;
	// the spirit fades in over this many client cycles before the mace reaches the furthest point of the throw
	private static final int SPIRIT_LEAD_CYCLES = 10;

	@Inject
	private Client client;

	@Inject
	private GatorModel gatorModel;

	@Inject
	private GatorObligatorConfig config;

	private final Set<Player> candidates = new HashSet<>();
	private final Map<Player, Throw> throwsByPlayer = new HashMap<>();
	private ThrustModel thrustModel;

	void onGraphicChanged(Actor actor)
	{
		if (actor instanceof Player && findThrust(actor) != null)
		{
			candidates.add((Player) actor);
		}
	}

	void onPlayerDespawned(Player player)
	{
		candidates.remove(player);
		remove(player);
	}

	void onBeforeRender()
	{
		Player local = client.getLocalPlayer();
		if (local != null && findThrust(local) != null)
		{
			// spotanims created client side, such as by the preview, do not fire GraphicChanged
			candidates.add(local);
		}

		for (Player player : candidates)
		{
			ActorSpotAnim spot = findThrust(player);
			if (spot != null)
			{
				replace(player, spot);
			}
		}
		candidates.clear();

		Iterator<Map.Entry<Player, Throw>> it = throwsByPlayer.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<Player, Throw> e = it.next();
			if (!update(e.getKey(), e.getValue()))
			{
				e.getValue().despawn();
				it.remove();
			}
		}
	}

	void clear()
	{
		for (Throw t : throwsByPlayer.values())
		{
			t.despawn();
		}
		throwsByPlayer.clear();
		candidates.clear();
		thrustModel = null;
	}

	private void replace(Player player, ActorSpotAnim spot)
	{
		int animId = ObligatorIds.thrustAnimation(spot.getId());
		ThrustModel thrust = thrustModel();
		Model spotModel = spot.getModel();
		if (animId == -1 || thrust == null)
		{
			return;
		}
		if (spotModel != null && !thrust.matches(spotModel))
		{
			// a variant using a different model, such as the uncharged thrust, is left as the game draws it
			log.debug("Thrust spotanim {} uses a different model ({} verts, {} faces), not replacing",
				spot.getId(), spotModel.getVerticesCount(), spotModel.getFaceCount());
			return;
		}

		Throw previous = throwsByPlayer.remove(player);
		if (previous != null)
		{
			previous.despawn();
		}

		GatorModel.Head head = gatorModel.createHead();
		if (head == null)
		{
			return;
		}

		Throw t = new Throw(client.createRuneLiteObject(), client.createRuneLiteObject(), head,
			client.loadAnimation(animId), spot.getStartCycle(), spot.getHeight());
		t.thrust.setModel(thrust.model);
		t.gator.setModel(head.model);
		throwsByPlayer.put(player, t);

		player.removeSpotAnim((int) spot.getHash());
	}

	/**
	 * @return false once the throw has finished and should be removed
	 */
	private boolean update(Player player, Throw t)
	{
		LocalPoint lp = player.getLocalLocation();
		if (lp == null || t.thrustAnimation == null)
		{
			return false;
		}

		int plane = player.getWorldView().getPlane();
		int orientation = player.getCurrentOrientation();
		if (t.finished)
		{
			// the spirit can still be finishing its chomp after the mace is back in hand
			t.hideThrust();
			return updateSpirit(t, lp, plane, orientation);
		}

		if (client.getGameCycle() < t.startCycle)
		{
			// spotanims can start after a delay
			return true;
		}

		if (t.thrust.getAnimationController() == null)
		{
			AnimationController controller = new AnimationController(client, t.thrustAnimation);
			controller.setOnFinished(c ->
			{
				// hold the last frame until the copy is removed on the next render. Letting the controller drop
				// the animation would draw one frame of the unanimated model, whose trail sits at the player's feet
				c.setFrame(Math.max(0, t.thrustAnimation.getNumFrames() - 1));
				t.finished = true;
			});
			t.thrust.setAnimationController(controller);
			t.thrustStartCycle = client.getGameCycle();
		}

		// the copy follows the player like the spotanim would
		int groundZ = Perspective.getTileHeight(client, lp, plane) - t.height;
		t.thrust.setLocation(lp, plane);
		t.thrust.setZ(groundZ);
		t.thrust.setOrientation(orientation);
		activate(t.thrust);

		placeHead(t, lp, plane, orientation);
		updateSpirit(t, lp, plane, orientation);
		return true;
	}

	/**
	 * How many cycles into the bite this throw is, negative before it starts.
	 *
	 * The bite is timed so its jaws snap shut as the mace reaches the furthest point of the throw, which is when the
	 * hit lands. A bite that would snap too late is played faster until then and at its own speed after, one that
	 * would snap too early starts later.
	 */
	private float biteCycle(Throw t)
	{
		ThrustModel thrust = thrustModel;
		if (t.bite == null || thrust == null || t.thrustStartCycle == -1)
		{
			return -1;
		}

		int elapsed = client.getGameCycle() - t.thrustStartCycle;
		int apex = thrust.stats(client, t.thrustAnimation).apexCycle;
		int chomp = gatorModel.getChompCycle();
		if (chomp <= apex)
		{
			return elapsed - (apex - chomp);
		}
		return elapsed < apex ? elapsed * chomp / (float) apex : chomp + elapsed - apex;
	}

	/**
	 * Shows a large translucent Zebak head doing the chomp, fading in as the mace flies out and out after the snap.
	 *
	 * @return whether the spirit is still showing or yet to show
	 */
	private boolean updateSpirit(Throw t, LocalPoint lp, int plane, int orientation)
	{
		ThrustModel thrust = thrustModel;
		if (!config.chomp() || t.bite == null || thrust == null || t.thrustStartCycle == -1)
		{
			return false;
		}

		float cycle = biteCycle(t);
		int duration = GatorModel.biteDuration(t.bite);
		if (cycle >= duration)
		{
			t.hideSpirit();
			return false;
		}

		int elapsed = client.getGameCycle() - t.thrustStartCycle;
		int apex = thrust.stats(client, t.thrustAnimation).apexCycle;
		int chomp = gatorModel.getChompCycle();
		int lead = Math.max(1, Math.min(apex, SPIRIT_LEAD_CYCLES));
		float fadeIn = (elapsed - (apex - lead)) / (float) lead;
		float fadeOut = (duration - cycle) / (float) Math.max(1, duration - chomp);
		float fade = Math.max(0, Math.min(1, Math.min(fadeIn, fadeOut)));
		if (cycle < 0 || fade <= 0 || t.spiritBasis == null)
		{
			t.hideSpirit();
			return !t.finished;
		}

		if (t.spirit == null)
		{
			t.spirit = gatorModel.createSpirit();
			if (t.spirit == null)
			{
				log.debug("Spirit could not be built");
				return false;
			}
			t.spiritObject = client.createRuneLiteObject();
			t.spiritObject.setModel(t.spirit.model);
		}

		// the spirit is posed like the thrown head and centred on the thrust's axis, which runs along the thrown head
		// from its neck to its snout. spiritPosition moves it back along the axis towards the player. It follows the
		// throw out, then stays at the furthest point to finish its chomp
		float back = gatorModel.getSpiritPosition();
		float fx = -t.spiritBasis[6], fy = -t.spiritBasis[7], fz = -t.spiritBasis[8];
		float px = t.spiritHead[0] - fx * back;
		float py = t.spiritHead[1] - fy * back;
		float pz = t.spiritHead[2] - fz * back;
		gatorModel.pose(t.spirit, t.bite, GatorModel.frameAt(t.bite, (int) cycle), gatorModel.spiritScaleFactor(),
			t.spiritBasis, GatorModel.Pivot.CENTER);
		GatorModel.setAlpha(t.spirit, 255 - Math.round((255 - gatorModel.getSpiritAlpha()) * fade));
		GatorModel.place(t.spiritObject, lp, plane, orientation, t.spiritGroundZ, px, py, pz);
		activate(t.spiritObject);
		return true;
	}

	private void placeHead(Throw t, LocalPoint lp, int plane, int orientation)
	{
		ThrustModel thrust = thrustModel;
		Model model = t.thrust.getModel();
		if (thrust == null || model == null)
		{
			t.hideHead();
			return;
		}

		// size the head like the ball, which is collapsed to a point while it is not thrown out
		ThrustStats stats = thrust.stats(client, t.thrustAnimation);
		float size = Math.min(ThrustModel.extent(model, thrust.ballVertices) / stats.ballExtent, 1);
		if (size < MIN_VISIBLE_SIZE)
		{
			t.hideHead();
			return;
		}

		float[] ball = ThrustModel.centroid(model, thrust.ballVertices);
		float[] spike = ThrustModel.centroid(model, thrust.spikeVertices);
		float sx = spike[0], sy = spike[1], sz = spike[2];
		float bx = ball[0], by = ball[1], bz = ball[2];
		if (sx == bx && sy == by && sz == bz)
		{
			t.hideHead();
			return;
		}

		float cycle = biteCycle(t);
		int frame = config.chomp() && t.bite != null ? GatorModel.frameAt(t.bite, (int) Math.floor(cycle)) : -1;

		// the neck sits on the spike the chain attaches to, with the snout pointing away from the chain
		float[] basis = GatorModel.uprightBasis(bx - sx, by - sy, bz - sz);
		float s = gatorModel.headScale() * size;
		GatorModel.Pose pose = gatorModel.pose(t.head, t.bite, frame, s, basis, GatorModel.Pivot.NECK);

		// the spike is in the thrust copy's space, which is placed like the player
		int groundZ = Perspective.getTileHeight(client, lp, plane) - t.height;
		GatorModel.place(t.gator, lp, plane, orientation, groundZ, sx, sy, sz);
		activate(t.gator);

		// remembered for the spirit: the thrown head's centre and its orientation along the thrust, at the furthest
		// point of the throw so far. So the spirit stays at the target rather than following the head back in
		float cx = sx + pose.centerX, cy = sy + pose.centerY, cz = sz + pose.centerZ;
		float reach = cx * cx + cz * cz;
		if (t.spiritHead == null || reach >= t.headReach)
		{
			t.spiritBasis = basis;
			t.spiritHead = new float[]{cx, cy, cz};
			t.headReach = reach;
			t.spiritGroundZ = groundZ;
		}
	}

	private ThrustModel thrustModel()
	{
		if (thrustModel == null)
		{
			ModelData md = client.loadModelData(ObligatorIds.THRUST_MODEL);
			if (md != null)
			{
				thrustModel = ThrustModel.build(md);
			}
		}
		return thrustModel;
	}

	private void remove(Player player)
	{
		Throw t = throwsByPlayer.remove(player);
		if (t != null)
		{
			t.despawn();
		}
	}

	private static void activate(RuneLiteObject object)
	{
		// setActive registers the object each time it is called, so only do it once
		if (!object.isActive())
		{
			object.setActive(true);
		}
	}

	private static ActorSpotAnim findThrust(Actor actor)
	{
		for (ActorSpotAnim spot : actor.getSpotAnims())
		{
			if (ObligatorIds.isThrustSpotanim(spot.getId()))
			{
				return spot;
			}
		}
		return null;
	}

	private final class Throw
	{
		final RuneLiteObject thrust;
		final RuneLiteObject gator;
		final GatorModel.Head head;
		final Animation thrustAnimation;
		final Animation bite;
		final int startCycle;
		final int height;
		// when the thrust copy started animating, which the bite is timed from
		int thrustStartCycle = -1;
		boolean finished;

		GatorModel.Head spirit;
		RuneLiteObject spiritObject;
		// the thrown head's orientation along the thrust, its centre (in the player model's space) and its ground
		// height at the furthest point of the throw so far, and that point's squared horizontal distance from the player
		float[] spiritBasis;
		float[] spiritHead;
		float headReach;
		int spiritGroundZ;

		Throw(RuneLiteObject thrust, RuneLiteObject gator, GatorModel.Head head, Animation thrustAnimation,
			int startCycle, int height)
		{
			this.thrust = thrust;
			this.gator = gator;
			this.head = head;
			this.thrustAnimation = thrustAnimation;
			this.bite = gatorModel.biteAnimation();
			this.startCycle = startCycle;
			this.height = height;
		}

		void hideHead()
		{
			deactivate(gator);
		}

		void hideThrust()
		{
			hideHead();
			deactivate(thrust);
		}

		void hideSpirit()
		{
			if (spiritObject != null)
			{
				deactivate(spiritObject);
			}
		}

		void despawn()
		{
			hideThrust();
			hideSpirit();
		}
	}

	private static void deactivate(RuneLiteObject object)
	{
		if (object.isActive())
		{
			object.setActive(false);
		}
	}

	/**
	 * The thrust model with its ball faces hidden, and the vertex indices of its parts. Parts are found by hue,
	 * which matches the colors seen on the spotanim in game.
	 */
	private static final class ThrustModel
	{
		final Model model;
		final int vertexCount;
		final int faceCount;
		final int[] ballVertices;
		final int[] spikeVertices;
		// measurements of each thrust animation, by animation id
		private final Map<Integer, ThrustStats> stats = new HashMap<>();

		private ThrustModel(Model model, int[] ballVertices, int[] spikeVertices)
		{
			this.model = model;
			this.vertexCount = model.getVerticesCount();
			this.faceCount = model.getFaceCount();
			this.ballVertices = ballVertices;
			this.spikeVertices = spikeVertices;
		}

		boolean matches(Model other)
		{
			return other.getVerticesCount() == vertexCount && other.getFaceCount() == faceCount;
		}

		/**
		 * Measures a thrust animation by posing the model at every frame: the ball's largest size, which can differ
		 * from the unanimated model, and when the ball is furthest from the player.
		 */
		ThrustStats stats(Client client, Animation animation)
		{
			return stats.computeIfAbsent(animation.getId(), id ->
			{
				float ballExtent = 0;
				float furthest = -1;
				int apexFrame = 0;
				for (int frame = 0; frame < animation.getNumFrames(); frame++)
				{
					Model posed = client.applyTransformations(model, animation, frame, null, 0);
					if (posed == null)
					{
						continue;
					}

					ballExtent = Math.max(ballExtent, extent(posed, ballVertices));
					float[] ball = centroid(posed, ballVertices);
					float reach = ball[0] * ball[0] + ball[2] * ball[2];
					if (reach > furthest)
					{
						furthest = reach;
						apexFrame = frame;
					}
				}

				// a frame is reached once the frames before it have played
				int apexCycle = apexFrame;
				if (!animation.isMayaAnim())
				{
					apexCycle = 0;
					int[] lengths = animation.getFrameLengths();
					for (int i = 0; i < apexFrame; i++)
					{
						apexCycle += lengths[i];
					}
				}

				log.debug("Thrust animation {}: ball size up to {}, furthest at frame {} (cycle {})", id, ballExtent,
					apexFrame, apexCycle);
				return new ThrustStats(ballExtent > 0 ? ballExtent : 1, Math.max(1, apexCycle));
			});
		}

		static float[] centroid(Model model, int[] vertices)
		{
			float[] x = model.getVerticesX();
			float[] y = model.getVerticesY();
			float[] z = model.getVerticesZ();
			float cx = 0, cy = 0, cz = 0;
			for (int v : vertices)
			{
				cx += x[v];
				cy += y[v];
				cz += z[v];
			}
			return new float[]{cx / vertices.length, cy / vertices.length, cz / vertices.length};
		}

		static ThrustModel build(ModelData md)
		{
			short[] colors = md.getFaceColors();
			int[] f1 = md.getFaceIndices1();
			int[] f2 = md.getFaceIndices2();
			int[] f3 = md.getFaceIndices3();
			List<Integer> ballFaces = new ArrayList<>();
			Set<Integer> ballVertices = new HashSet<>();
			Set<Integer> spikeFaces = new HashSet<>();
			for (int i = 0; i < md.getFaceCount(); i++)
			{
				int hue = (colors[i] >> 10) & 63;
				if (ObligatorIds.isBallHue(hue))
				{
					ballFaces.add(i);
					ballVertices.add(f1[i]);
					ballVertices.add(f2[i]);
					ballVertices.add(f3[i]);
				}
				else if (hue == ObligatorIds.SPIKE_HUE)
				{
					spikeFaces.add(i);
				}
			}

			int[] ball = ballVertices.stream().mapToInt(Integer::intValue).toArray();
			int[] spike = ObligatorIds.spikeVertices(md.getVerticesX(), md.getVerticesY(), md.getVerticesZ(),
				f1, f2, f3, spikeFaces.stream().mapToInt(Integer::intValue).toArray(), ball);
			if (ball.length == 0 || spike.length == 0)
			{
				log.debug("Thrust model {} has no ball or spike faces", ObligatorIds.THRUST_MODEL);
				return null;
			}

			Model model = md.shallowCopy().cloneColors().light();
			int[] colors3 = model.getFaceColors3();
			for (int face : ballFaces)
			{
				colors3[face] = -2;
			}

			log.debug("Thrust model {}: {} ball faces, {} ball vertices, {} spike vertices",
				ObligatorIds.THRUST_MODEL, ballFaces.size(), ball.length, spike.length);
			return new ThrustModel(model, ball, spike);
		}

		static float extent(Model model, int[] vertices)
		{
			return extent(model.getVerticesX(), model.getVerticesY(), model.getVerticesZ(), vertices);
		}

		/**
		 * The largest of the width, height and depth spanned by the given vertices.
		 */
		static float extent(float[] x, float[] y, float[] z, int[] vertices)
		{
			float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
			float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
			for (int v : vertices)
			{
				minX = Math.min(minX, x[v]);
				maxX = Math.max(maxX, x[v]);
				minY = Math.min(minY, y[v]);
				maxY = Math.max(maxY, y[v]);
				minZ = Math.min(minZ, z[v]);
				maxZ = Math.max(maxZ, z[v]);
			}
			return Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
		}
	}

	private static final class ThrustStats
	{
		// the ball's size when fully thrown out
		final float ballExtent;
		// client cycles into the animation at which the ball is furthest from the player
		final int apexCycle;

		ThrustStats(float ballExtent, int apexCycle)
		{
			this.ballExtent = ballExtent;
			this.apexCycle = apexCycle;
		}
	}
}
