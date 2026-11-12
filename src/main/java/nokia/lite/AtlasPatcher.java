package nokia.lite;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Swaps single block textures inside the live block atlas (no resource reload, no chunk rebuild). The atlas is drawn
 * from per-sprite textures by a render pass; {@link #draw} repeats exactly that pass for one sprite with other pixels.
 */
final class AtlasPatcher {
	private static final Logger LOGGER = LoggerFactory.getLogger("NokiaLite/Atlas");

	private record Patched(TextureAtlasSprite vanilla, SpriteContents alt, SpriteContents.AnimationState removed, Path file, long stamp) { }

	/** Vanilla texture name (e.g. "clay") -> what is in the atlas for it now. */
	private static final Map<String, Patched> applied = new HashMap<>();
	private static final Set<String> failed = new HashSet<>();
	private static boolean warnedAtlas;

	/** The sprite constructor is protected. */
	private static final class Alt extends TextureAtlasSprite {
		Alt(Identifier loc, SpriteContents c, int w, int h, int x, int y, int pad) { super(loc, c, w, h, x, y, pad); }
	}

	private static Field field(Class<?> c, String name) {
		try {
			Field f = c.getDeclaredField(name);
			f.setAccessible(true);
			return f;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static final Field MAX_MIP = field(TextureAtlas.class, "maxMipLevel");
	private static final Field MIP_COUNT = field(TextureAtlas.class, "mipLevelCount");
	private static final Field WIDTH = field(TextureAtlas.class, "width");
	private static final Field HEIGHT = field(TextureAtlas.class, "height");
	private static final Field MIP_VIEWS = field(TextureAtlas.class, "mipViews");
	private static final Field STATES = field(TextureAtlas.class, "animatedTexturesStates");
	private static final Field PADDING = field(TextureAtlasSprite.class, "padding");
	private static final Field SUB_FRAME = field(SpriteContents.AnimationState.class, "subFrame");
	private static final Field ANIM_INFO = field(SpriteContents.AnimationState.class, "animationInfo");
	private static final Field ANIM_TEXTURE = field(SpriteContents.class, "animatedTexture");

	private static Object get(Field f, Object o) {
		try { return f.get(o); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
	}

	private static int getInt(Field f, Object o) { return (Integer) get(f, o); }

	@SuppressWarnings("unchecked")
	private static List<SpriteContents.AnimationState> states(TextureAtlas atlas) {
		return (List<SpriteContents.AnimationState>) get(STATES, atlas);
	}

	private static void set(Field f, Object o, Object v) {
		try { f.set(o, v); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
	}

	private static Identifier id(String name) { return Identifier.withDefaultNamespace("block/" + name); }

	static String summary() {
		return applied.size() + " swapped " + applied.keySet() + ", " + failed.size() + " failed " + failed;
	}

	private static long stamp(Path p) {
		try { return Files.getLastModifiedTime(p).toMillis(); } catch (java.io.IOException e) { return 0; }
	}

	/** Makes the atlas show {@code desired} (vanilla texture name -> image file) and nothing else. */
	static void sync(Map<String, Path> desired) {
		Minecraft mc = Minecraft.getInstance();
		TextureAtlas atlas;
		try {
			atlas = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
		} catch (RuntimeException e) {
			if (!warnedAtlas) { warnedAtlas = true; LOGGER.warn("no block atlas", e); }
			return;
		}
		try {
			// A resource reload built a fresh atlas: our patches are gone with it.
			applied.entrySet().removeIf(e -> {
				boolean stale = atlas.getSprite(id(e.getKey())) != e.getValue().vanilla();
				if (stale) e.getValue().alt().close();
				return stale;
			});
			for (String n : new ArrayList<>(applied.keySet())) {
				Path want = desired.get(n);
				Patched p = applied.get(n);
				// gone, or another file, or the file was edited: take it out (an edited one is drawn again below)
				if (want == null || !want.equals(p.file()) || stamp(want) != p.stamp()) revert(atlas, n);
			}
			failed.removeIf(n -> !desired.containsKey(n)); // asked for again later: try again
			for (Map.Entry<String, Path> e : desired.entrySet()) {
				if (!applied.containsKey(e.getKey()) && !failed.contains(e.getKey())) patch(mc, atlas, e.getKey(), e.getValue());
			}
		} catch (Throwable t) {
			LOGGER.warn("texture swap failed", t);
		}
	}

	private static void patch(Minecraft mc, TextureAtlas atlas, String name, Path file) {
		TextureAtlasSprite vanilla = atlas.getSprite(id(name));
		if (vanilla == atlas.missingSprite()) {
			LOGGER.warn("block atlas has no sprite {}", id(name));
			failed.add(name);
			return;
		}
		SpriteContents vc = vanilla.contents();
		long stamp = stamp(file);
		NativeImage image;
		try (InputStream in = Files.newInputStream(file)) {
			image = fit(NativeImage.read(in), vc.width(), vc.height());
		} catch (Exception e) {
			LOGGER.warn("cannot read texture {}", file, e);
			failed.add(name);
			return;
		}
		SpriteContents alt = new SpriteContents(Identifier.fromNamespaceAndPath("nokiaqol_lite", "custom/" + name), new FrameSize(image.getWidth(), image.getHeight()), image);
		alt.increaseMipLevel(getInt(MAX_MIP, atlas));
		// A vanilla animation would redraw over the swap every few ticks: park it while swapped.
		SpriteContents.AnimationState removed = null;
		if (vanilla.isAnimated()) {
			List<SpriteContents.AnimationState> list = states(atlas);
			Object info = get(ANIM_TEXTURE, vc);
			for (SpriteContents.AnimationState s : list) if (get(ANIM_INFO, s) == info) { removed = s; break; }
			if (removed != null) {
				List<SpriteContents.AnimationState> rest = new ArrayList<>(list);
				rest.remove(removed);
				set(STATES, atlas, rest);
			}
		}
		draw(atlas, vanilla, alt);
		LOGGER.info("swapped {} -> {}", name, file);
		applied.put(name, new Patched(vanilla, alt, removed, file, stamp));
	}

	/** The image at the size of the texture it replaces: the first square frame of a tall strip, scaled if it is another size. */
	private static NativeImage fit(NativeImage src, int w, int h) {
		if (src.getWidth() == w && src.getHeight() == h) return src;
		NativeImage out = new NativeImage(w, h, false);
		int side = Math.min(src.getWidth(), src.getHeight());
		src.resizeSubRectTo(0, 0, side, side, out);
		src.close();
		return out;
	}

	private static void revert(TextureAtlas atlas, String name) {
		Patched p = applied.remove(name);
		if (p == null) return;
		if (p.removed() != null) {
			// Back into the animation list, and past its frame time so it redraws on the next tick.
			List<SpriteContents.AnimationState> list = new ArrayList<>(states(atlas));
			list.add(p.removed());
			set(STATES, atlas, list);
			try { SUB_FRAME.setInt(p.removed(), 1_000_000); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
		} else {
			draw(atlas, p.vanilla(), p.vanilla().contents());
		}
		p.alt().close();
	}

	/** Draws {@code contents} into the atlas where {@code at} sits: the pass TextureAtlas.uploadInitialContents runs per static sprite. */
	private static void draw(TextureAtlas atlas, TextureAtlasSprite at, SpriteContents contents) {
		GpuDevice device = RenderSystem.getDevice();
		int maxMip = getInt(MAX_MIP, atlas);
		int levels = getInt(MIP_COUNT, atlas);
		int aw = getInt(WIDTH, atlas);
		int ah = getInt(HEIGHT, atlas);
		TextureAtlasSprite sprite = new Alt(atlas.location(), contents, aw, ah, at.getX(), at.getY(), getInt(PADDING, at));
		int uboSize = Mth.roundToward(SpriteContents.UBO_SIZE, device.getDeviceInfo().limits().minUniformOffsetAlignment());
		GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true);
		ByteBuffer buffer = MemoryUtil.memAlloc(uboSize * levels);
		sprite.uploadSpriteUbo(buffer, 0, maxMip, aw, ah, uboSize);
		GpuTexture scratch = device.createTexture(() -> "NokiaLite sprite", 5, GpuFormat.RGBA8_UNORM, contents.width(), contents.height(), 1, levels);
		GpuTextureView[] views = new GpuTextureView[levels];
		for (int level = 0; level <= maxMip; level++) {
			sprite.uploadFirstFrame(scratch, level);
			views[level] = device.createTextureView(scratch);
		}
		GpuBuffer ubo = device.createBuffer(() -> "NokiaLite sprite UBO", 128, buffer);
		GpuTextureView[] mipViews = (GpuTextureView[]) get(MIP_VIEWS, atlas);
		try {
			for (int level = 0; level < levels; level++) {
				try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "NokiaLite swap", mipViews[level], Optional.empty())) {
					RenderSystem.bindDefaultUniforms(pass);
					pass.setPipeline(RenderPipelines.ANIMATE_SPRITE_BLIT);
					pass.bindTexture("Sprite", views[level], sampler);
					pass.setUniform("SpriteAnimationInfo", ubo.slice((long) level * uboSize, SpriteContents.UBO_SIZE));
					pass.draw(6, 1, 0, 0);
				}
			}
		} finally {
			ubo.close();
			for (GpuTextureView v : views) if (v != null) v.close();
			scratch.close();
			MemoryUtil.memFree(buffer);
		}
	}
}
