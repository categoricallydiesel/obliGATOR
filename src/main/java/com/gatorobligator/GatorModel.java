package com.gatorobligator;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.NpcID;

/**
 * Builds the Zebak head, and the Zebak spirit that appears for the chomp, from the game cache.
 *
 * RuneLiteObjects can only be rotated about the vertical axis, so full orientations are applied to the vertices
 * each frame instead. The bite animation is applied to the full size model and the result is scaled down afterwards,
 * so the animation's translations shrink along with the model.
 */
@Slf4j
@Singleton
class GatorModel
{
	// depth of the slice behind the cut whose vertices make up the neck, in unscaled Zebak model units
	private static final int NECK_DEPTH = 60;
	// room in a model's bounds beyond its rest pose, for the bite animation moving vertices further out
	private static final float BOUNDS_MARGIN = 1.5f;
	// the spirit is tinted the green of the Obligator's charged trail
	private static final int SPIRIT_HUE = 15;
	private static final int SPIRIT_SATURATION = 5;

	@Inject
	private Client client;

	// Tunables, adjustable with ::oblig commands in developer mode until good values are found

	/** Head size as a fraction of 128 */
	@Getter
	@Setter
	private int scale = 20;

	/** Faces whose centroid z is at or beyond this (in unscaled Zebak model units) are hidden, cropping off the body */
	@Getter
	@Setter
	private int cutZ = -650;

	/** Faces whose centroid y is at or below this are hidden, cropping off the legs. Up is negative y */
	@Getter
	@Setter
	private int cutY = -400;

	@Getter
	@Setter
	private int biteAnimId = AnimationID.NPC_ZEBAK01_ATTACK_MELEE;

	/** Spirit size as a fraction of 128 */
	@Getter
	@Setter
	private int spiritScale = 64;

	/** Spirit transparency, from 0 (solid) to 255 (invisible) */
	@Getter
	@Setter
	private int spiritAlpha = 128;

	/** Whether the spirit is the whole Zebak or only the cropped head */
	@Getter
	@Setter
	private boolean spiritFull;

	/** How far back along the thrust, towards the player, the spirit is centred from the thrown head */
	@Getter
	@Setter
	private int spiritPosition = 70;

	/**
	 * Client cycles into the bite at which it strikes, which is timed to land on the hit. Set by eye, as sampling the
	 * bite's frames up front poses every frame the same
	 */
	@Getter
	@Setter
	private int chompCycle = 60;

	private ModelData modelData;
	private Model base;
	@Getter
	private int version;

	void invalidate()
	{
		version++;
	}

	void reset()
	{
		modelData = null;
		base = null;
		invalidate();
	}

	ModelData modelData()
	{
		if (modelData == null)
		{
			NPCComposition def = client.getNpcDefinition(NpcID.TOA_ZEBAK);
			int[] ids = def.getModels();
			ModelData[] parts = new ModelData[ids.length];
			for (int i = 0; i < ids.length; i++)
			{
				parts[i] = client.loadModelData(ids[i]);
				if (parts[i] == null)
				{
					return null;
				}
			}

			ModelData merged = client.mergeModels(parts);
			short[] from = def.getColorToReplace();
			short[] to = def.getColorToReplaceWith();
			if (from != null && to != null)
			{
				for (int i = 0; i < from.length; i++)
				{
					merged.recolor(from[i], to[i]);
				}
			}
			modelData = merged;
		}
		return modelData;
	}

	/**
	 * Full size, unanimated model the bite animation is applied to. Never modified.
	 */
	Model base()
	{
		if (base == null)
		{
			ModelData md = modelData();
			if (md != null)
			{
				base = md.shallowCopy().cloneVertices().light();
			}
		}
		return base;
	}

	Animation biteAnimation()
	{
		return client.loadAnimation(biteAnimId);
	}

	/**
	 * Length of a bite in client cycles.
	 */
	static int biteDuration(Animation bite)
	{
		if (bite.isMayaAnim())
		{
			return bite.getDuration();
		}

		int total = 0;
		for (int length : bite.getFrameLengths())
		{
			total += length;
		}
		return total;
	}

	/**
	 * The bite's frame after the given number of client cycles, or -1 outside of it. Zebak's bite is a maya
	 * animation, whose frames are client cycles rather than having their own lengths.
	 */
	static int frameAt(Animation bite, int cycle)
	{
		if (cycle < 0)
		{
			return -1;
		}
		if (bite.isMayaAnim())
		{
			return cycle < bite.getDuration() ? cycle : -1;
		}

		int[] lengths = bite.getFrameLengths();
		for (int i = 0; i < lengths.length; i++)
		{
			cycle -= lengths[i];
			if (cycle < 0)
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * The cropped head for one mace. Each instance owns its vertices since every head is posed independently.
	 */
	Head createHead()
	{
		ModelData md = modelData();
		return md == null ? null : build(md.shallowCopy().cloneVertices().cloneColors(), true, headScale());
	}

	/**
	 * A translucent green Zebak that appears to do the chomp.
	 */
	Head createSpirit()
	{
		ModelData md = modelData();
		if (md == null)
		{
			return null;
		}

		ModelData spirit = md.shallowCopy().cloneVertices().cloneColors().cloneTransparencies(true);
		short[] colors = spirit.getFaceColors();
		byte[] alphas = spirit.getFaceTransparencies();
		for (int i = 0; i < colors.length; i++)
		{
			// keep the luminance so the spirit's shape still reads
			colors[i] = (short) (SPIRIT_HUE << 10 | SPIRIT_SATURATION << 7 | colors[i] & 127);
			alphas[i] = (byte) spiritAlpha;
		}
		return build(spirit, !spiritFull, spiritScaleFactor());
	}

	/**
	 * @param maxScale the largest scale the model will be posed at, used to size its bounds
	 */
	private Head build(ModelData md, boolean crop, float maxScale)
	{
		Model base = base();
		if (base == null)
		{
			return null;
		}

		Model display = md.light();
		float[] x = base.getVerticesX();
		float[] y = base.getVerticesY();
		float[] z = base.getVerticesZ();
		int[] f1 = base.getFaceIndices1();
		int[] f2 = base.getFaceIndices2();
		int[] f3 = base.getFaceIndices3();
		int[] colors3 = display.getFaceColors3();

		// the neck is the faces just in front of the cut, which is where the chain attaches
		Set<Integer> neckVertices = new HashSet<>();
		Set<Integer> headVertices = new HashSet<>();
		int kept = 0;
		for (int i = 0; i < display.getFaceCount(); i++)
		{
			float cy = (y[f1[i]] + y[f2[i]] + y[f3[i]]) / 3;
			float cz = (z[f1[i]] + z[f2[i]] + z[f3[i]]) / 3;
			boolean inHead = cz < cutZ && cy < cutY;
			if (crop && !inHead)
			{
				colors3[i] = -2;
				continue;
			}

			kept++;
			if (inHead)
			{
				Set<Integer> part = cz >= cutZ - NECK_DEPTH ? neckVertices : headVertices;
				part.add(f1[i]);
				part.add(f2[i]);
				part.add(f3[i]);
			}
		}
		headVertices.addAll(neckVertices);

		if (kept == 0 || neckVertices.isEmpty())
		{
			log.debug("Gator model has no faces with cut z={} y={}", cutZ, cutY);
			return null;
		}

		Head head = new Head(display, toArray(neckVertices), toArray(headVertices), version);
		reserveBounds(head, maxScale);
		return head;
	}

	private static int[] toArray(Set<Integer> set)
	{
		return set.stream().mapToInt(Integer::intValue).toArray();
	}

	/**
	 * The centre of the given vertices.
	 */
	private static float[] centroid(Model model, int[] vertices)
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

	/**
	 * The client computes a model's bounds the first time they are asked for and keeps them, even after its vertices
	 * move. Posing moves every vertex, and the GPU plugin depth sorts translucent models within those bounds, failing
	 * for vertices outside them. So before the first pose, compute the bounds once over a shape large enough to hold
	 * the model in any pose: its furthest vertex from the neck or the head's centre, whichever it is pinned by,
	 * scaled, with room for the bite moving vertices around.
	 */
	private void reserveBounds(Head head, float maxScale)
	{
		float[] bx = base.getVerticesX();
		float[] by = base.getVerticesY();
		float[] bz = base.getVerticesZ();
		float[] neck = centroid(base, head.neckVertices);
		float[] center = centroid(base, head.headVertices);
		float furthest = 0;
		for (int i = 0; i < base.getVerticesCount(); i++)
		{
			for (float[] pivot : new float[][]{neck, center})
			{
				float dx = bx[i] - pivot[0], dy = by[i] - pivot[1], dz = bz[i] - pivot[2];
				furthest = Math.max(furthest, dx * dx + dy * dy + dz * dz);
			}
		}
		float reach = (float) Math.sqrt(furthest) * maxScale * BOUNDS_MARGIN;

		float[] x = head.model.getVerticesX();
		float[] y = head.model.getVerticesY();
		float[] z = head.model.getVerticesZ();
		Arrays.fill(x, 0);
		Arrays.fill(y, 0);
		Arrays.fill(z, 0);
		// one vertex out to the side and above, one out in front and below, so the bounds cover reach in every direction
		x[0] = reach;
		y[0] = -reach;
		z[1] = reach;
		y[1] = reach;
		head.model.calculateBoundsCylinder();
	}

	/**
	 * Sets the transparency of a spirit's faces, from 0 (solid) to 255 (invisible).
	 */
	static void setAlpha(Head spirit, int alpha)
	{
		byte[] alphas = spirit.model.getFaceTransparencies();
		if (alphas != null)
		{
			Arrays.fill(alphas, (byte) Math.max(0, Math.min(255, alpha)));
		}
	}

	/**
	 * Poses a model at a bite animation frame (-1 for at rest), scaled by s and rotated by basis (from
	 * {@link #uprightBasis}) about its neck, which ends up at the model's origin.
	 *
	 * Keeping the model around its origin, and moving the object to place it instead, keeps every vertex inside the
	 * bounds the client computed for the full size model. The GPU plugin depth sorts translucent models within those
	 * bounds and fails for vertices outside them.
	 */
	Pose pose(Head head, Animation bite, int frame, float s, float[] basis, Pivot pivot)
	{
		Model source = base;
		if (bite != null && frame >= 0)
		{
			Model animated = client.applyTransformations(base, bite, frame, null, 0);
			if (animated != null)
			{
				source = animated;
			}
		}

		// Zebak's bite moves its whole head as it rears and lunges, not only the jaw. Pinning the neck or centre where
		// it is in this frame keeps the head on the chain, while still showing the bite's tilt and jaw
		float[] center = centroid(source, head.headVertices);
		float[] p = pivot == Pivot.NECK ? centroid(source, head.neckVertices) : center;

		float[] sx = source.getVerticesX();
		float[] sy = source.getVerticesY();
		float[] sz = source.getVerticesZ();
		float[] dx = head.model.getVerticesX();
		float[] dy = head.model.getVerticesY();
		float[] dz = head.model.getVerticesZ();
		int count = Math.min(source.getVerticesCount(), head.model.getVerticesCount());
		for (int i = 0; i < count; i++)
		{
			float lx = (sx[i] - p[0]) * s;
			float ly = (sy[i] - p[1]) * s;
			float lz = (sz[i] - p[2]) * s;
			dx[i] = lx * basis[0] + ly * basis[3] + lz * basis[6];
			dy[i] = lx * basis[1] + ly * basis[4] + lz * basis[7];
			dz[i] = lx * basis[2] + ly * basis[5] + lz * basis[8];
		}
		head.model.calculateBoundsCylinder();

		float lx = (center[0] - p[0]) * s;
		float ly = (center[1] - p[1]) * s;
		float lz = (center[2] - p[2]) * s;
		return new Pose(
			lx * basis[0] + ly * basis[3] + lz * basis[6],
			lx * basis[1] + ly * basis[4] + lz * basis[7],
			lx * basis[2] + ly * basis[5] + lz * basis[8]);
	}

	/**
	 * The point of a model that is kept fixed while posing, and ends up at the model's origin.
	 */
	enum Pivot
	{
		/** Where the chain attaches */
		NECK,
		/** The middle of the head */
		CENTER,
	}

	/**
	 * Where the middle of the head ended up, relative to the pivot.
	 */
	static final class Pose
	{
		final float centerX;
		final float centerY;
		final float centerZ;

		private Pose(float centerX, float centerY, float centerZ)
		{
			this.centerX = centerX;
			this.centerY = centerY;
			this.centerZ = centerZ;
		}
	}

	/**
	 * Places an object at a point given in an actor's unrotated model space, such as a point on the player's model
	 * or on the thrust copy, for an actor at lp facing orientation and standing at groundZ.
	 */
	static void place(RuneLiteObject object, LocalPoint lp, int plane, int orientation, int groundZ,
		float x, float y, float z)
	{
		double angle = orientation * Perspective.UNIT;
		double sin = Math.sin(angle);
		double cos = Math.cos(angle);
		object.setLocation(new LocalPoint(
			lp.getX() + (int) Math.round(x * cos + z * sin),
			lp.getY() + (int) Math.round(z * cos - x * sin),
			lp.getWorldView()), plane);
		object.setZ(groundZ + Math.round(y));
		object.setOrientation(orientation);
	}

	float headScale()
	{
		return scale / 128f;
	}

	float spiritScaleFactor()
	{
		return spiritScale / 128f;
	}

	/**
	 * Rotation that points the snout along (fx, fy, fz) without rolling. The top of the head faces up while the snout
	 * is level, and turns smoothly to face the player's front (-z) as the snout points straight up or down, so there
	 * is no sudden flip when hanging. Returns where the model's x, y and z axes end up, as three consecutive vectors.
	 *
	 * In the Zebak model the snout points along -z and up is -y, as in all models.
	 */
	static float[] uprightBasis(float fx, float fy, float fz)
	{
		float len = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
		fx /= len;
		fy /= len;
		fz /= len;

		float level = (float) Math.sqrt(fx * fx + fz * fz);
		float[] u = perpendicular(fx, fy, fz, 0, -level, -(1 - level));
		if (u == null)
		{
			// the blended reference is parallel to the snout, which only happens pointing up and forwards
			u = perpendicular(fx, fy, fz, 0, -1, 0);
		}
		float ux = u[0], uy = u[1], uz = u[2];

		// the model's x axis is up x snout, which keeps the rotation from mirroring the head
		float rx = uy * fz - uz * fy;
		float ry = uz * fx - ux * fz;
		float rz = ux * fy - uy * fx;

		// the model's y axis points down and its z axis points backwards
		return new float[]{rx, ry, rz, -ux, -uy, -uz, -fx, -fy, -fz};
	}

	/**
	 * The part of (rx, ry, rz) perpendicular to the unit vector f, normalised, or null if they are near parallel.
	 */
	private static float[] perpendicular(float fx, float fy, float fz, float rx, float ry, float rz)
	{
		float dot = rx * fx + ry * fy + rz * fz;
		rx -= dot * fx;
		ry -= dot * fy;
		rz -= dot * fz;
		float len = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
		if (len < 0.1f)
		{
			return null;
		}
		return new float[]{rx / len, ry / len, rz / len};
	}

	static final class Head
	{
		final Model model;
		// vertices just in front of the cut, where the chain attaches
		final int[] neckVertices;
		// all vertices of the head, including the neck
		final int[] headVertices;
		final int version;

		private Head(Model model, int[] neckVertices, int[] headVertices, int version)
		{
			this.model = model;
			this.neckVertices = neckVertices;
			this.headVertices = headVertices;
			this.version = version;
		}
	}
}
