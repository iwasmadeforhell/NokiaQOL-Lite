package nokia.lite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.scores.DisplaySlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The ore skins: per group a texture per slot (a file in NokiaQOL/textures), a break sound (a file in NokiaQOL/sounds) and the places it applies. */
public final class Skins {
	private static final Logger LOGGER = LoggerFactory.getLogger("NokiaLite");

	/** Break-sound choices that are not files. */
	public static final String DEFAULT = "Default", SILENT = "Silent";

	public static final int LOC_MINES = 1, LOC_CRYSTAL = 2, LOC_SHAFT = 4, LOC_ELSE = 8, LOC_ALL = 15;
	/** Button letter and tooltip per LOC_* bit, in that order. */
	public static final String[] LOC_LETTER = {"D", "C", "S", "E"};
	public static final String[] LOC_NAME = {"Dwarven Mines / Glacite Tunnels", "Crystal Hollows", "Glacite Mineshafts", "Everywhere else"};

	// The Dwarven Mines, Glacite Tunnels and Base Camp are one server; the tab list says "Dwarven Mines" there.
	private static final Pattern MINES = Pattern.compile("dwarven mines|glacite tunnels|base camp", Pattern.CASE_INSENSITIVE);
	private static final Pattern CRYSTAL = Pattern.compile("crystal hollows", Pattern.CASE_INSENSITIVE);
	private static final Pattern SHAFT = Pattern.compile("mineshaft", Pattern.CASE_INSENSITIVE);

	/** The LOC_* bit(s) for where you are now (LOC_ELSE when none of the three). */
	static volatile int here = LOC_ELSE;

	/** One replaceable texture: the vanilla block textures it covers and the file it uses (default: the built-in one). */
	public static final class Slot {
		public final String file;
		final String[] vanilla;
		/** File name in NokiaQOL/textures, e.g. "amber.png". */
		public volatile String chosen;

		Slot(String file, String... vanilla) { this.file = file; this.vanilla = vanilla; this.chosen = file + ".png"; }

		/** The file to draw: the chosen one, else the built-in name; null if neither exists. */
		Path path() {
			Path p = Folders.TEXTURES.resolve(chosen);
			if (Files.isRegularFile(p)) return p;
			p = Folders.TEXTURES.resolve(file + ".png");
			return Files.isRegularFile(p) ? p : null;
		}
	}

	/** blocks: registry names (break sound). locs: LOC_* bits it applies in. */
	public static final class Skin {
		public final String id, label;
		final String[] blocks;
		public final List<Slot> slots;
		public volatile boolean on = true;
		/** DEFAULT, SILENT or a file name in NokiaQOL/sounds. */
		public volatile String sound;
		public volatile int locs;
		private final String defSound;
		private final int defLocs;

		Skin(String id, String label, int locs, String blocks, String sound, Slot... slots) {
			this.id = id; this.label = label; this.locs = locs; this.blocks = blocks.split(" "); this.sound = sound; this.slots = List.of(slots);
			this.defSound = sound; this.defLocs = locs;
		}

		void reset() {
			on = true;
			sound = defSound;
			locs = defLocs;
			for (Slot slot : slots) slot.chosen = slot.file + ".png";
		}

		/** False while you are somewhere it is not switched on for. */
		boolean active() { return (locs & here) != 0; }
	}

	private static Slot slot(String file, String... vanilla) { return new Slot(file, vanilla); }

	private static String join(String suffix, String... names) {
		List<String> out = new ArrayList<>();
		for (String n : names) out.add(n + suffix);
		return String.join(" ", out);
	}

	private static final String[] GEM_COLORS = "orange purple lime magenta red light_blue yellow white black blue brown green".split(" ");
	private static final String[] GEM_NAMES = "amber amethyst jade jasper ruby sapphire topaz opal onyx aquamarine citrine peridot".split(" ");
	private static final String[] ORE_NAMES = "copper iron gold diamond emerald lapis redstone".split(" ");

	private static Slot[] gemSlots() {
		Slot[] s = new Slot[GEM_NAMES.length];
		for (int i = 0; i < s.length; i++) s[i] = slot(GEM_NAMES[i], GEM_COLORS[i] + "_stained_glass");
		return s;
	}

	private static Slot[] oreSlots() {
		List<Slot> s = new ArrayList<>();
		for (String o : ORE_NAMES) { s.add(slot(o, o + "_ore")); s.add(slot(o + "_deepslate", "deepslate_" + o + "_ore")); }
		s.add(slot("nether_gold", "nether_gold_ore"));
		s.add(slot("nether_quartz", "nether_quartz_ore"));
		return s.toArray(new Slot[0]);
	}

	private static String oreBlocks() {
		List<String> o = new ArrayList<>();
		for (String m : ORE_NAMES) { o.add(m + "_ore"); o.add("deepslate_" + m + "_ore"); }
		o.add("nether_gold_ore");
		o.add("nether_quartz_ore");
		return String.join(" ", o);
	}

	public static final List<Skin> ALL = List.of(
		new Skin("mithril", "Mithril", LOC_ALL, "prismarine prismarine_bricks dark_prismarine gray_wool cyan_terracotta light_blue_wool", "nokia.ogg",
			slot("mithril_green", "prismarine"), slot("mithril_green_bricks", "prismarine_bricks"), slot("mithril_green_dark", "dark_prismarine"),
			slot("mithril_gray", "gray_wool"), slot("mithril_gray_dark", "cyan_terracotta"), slot("mithril_blue", "light_blue_wool")),
		new Skin("titanium", "Titanium", LOC_ALL, "polished_diorite", "nokia.ogg", slot("titanium", "polished_diorite")),
		new Skin("gemstones", "Gemstones", LOC_MINES | LOC_CRYSTAL | LOC_SHAFT, join("_stained_glass", GEM_COLORS) + " " + join("_stained_glass_pane", GEM_COLORS),
			"amethyst.ogg", gemSlots()),
		new Skin("tungsten", "Tungsten", LOC_MINES | LOC_SHAFT, "clay cobblestone", "nokia.ogg", slot("tungsten", "clay"), slot("tungsten_cobble", "cobblestone")),
		new Skin("umber", "Umber", LOC_MINES | LOC_SHAFT, "terracotta brown_terracotta red_sandstone smooth_red_sandstone", "nokia.ogg",
			slot("umber_terracotta", "terracotta"), slot("umber_brown", "brown_terracotta"),
			slot("umber", "red_sandstone", "red_sandstone_top", "red_sandstone_bottom")),
		new Skin("hardstone", "Hardstone", LOC_ALL, "stone", "nokia.ogg", slot("hardstone", "stone")),
		new Skin("coal", "Coal", LOC_ALL, "coal_ore deepslate_coal_ore", "nokia.ogg", slot("coal", "coal_ore"), slot("coal_deepslate", "deepslate_coal_ore")),
		new Skin("ores", "Other ores", LOC_ALL, oreBlocks(), "nokia.ogg", oreSlots())
	);

	/** Tab-list "Area:" lines plus the sidebar location lines, joined. */
	static volatile String areaText = "";
	/** Set when a switch changes so the atlas is updated on the next tick. */
	public static volatile boolean dirty = true;

	private static Map<Block, Skin> byBlock;

	private static Map<Block, Skin> byBlock() {
		if (byBlock == null) {
			Map<Block, Skin> map = new HashMap<>();
			for (Skin s : ALL) for (String n : s.blocks) {
				Block b = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(n));
				if (b != Blocks.AIR) map.put(b, s);
			}
			byBlock = map;
		}
		return byBlock;
	}

	/** Called from the mixin: plays the chosen sound for breaking {@code state}. True = handled (skip the game's own sound). */
	public static boolean handleBreak(BlockState state) {
		Skin s = byBlock().get(state.getBlock());
		if (s == null || !s.active() || s.sound.equals(DEFAULT)) return false;
		if (s.sound.equals(SILENT)) return true;
		if (!Sounds.exists(s.sound)) return false; // file deleted: the game's own sound
		Sounds.play(s.sound, Sounds.BREAK_GAIN * Sounds.volume(SoundSource.BLOCKS));
		return true;
	}

	private static int tick;

	static void tick(Minecraft mc) {
		tick++;
		if (tick == 40) Folders.exportDefaults(mc); // after the resource manager is up
		if (tick % 20 == 0) readArea(mc);
		if (tick > 40 && (dirty || tick % 10 == 0)) {
			dirty = false;
			Map<String, Path> want = new HashMap<>();
			for (Skin s : ALL) if (s.on && s.active()) for (Slot slot : s.slots) {
				Path p = slot.path();
				if (p != null) for (String v : slot.vanilla) want.put(v, p);
			}
			AtlasPatcher.sync(want);
		}
	}

	static String readAreaText(Minecraft mc) {
		if (mc.level == null || mc.getConnection() == null) return "";
		List<String> parts = new ArrayList<>();
		mc.getConnection().getOnlinePlayers().forEach(e -> {
			if (e.getTabListDisplayName() == null) return;
			String t = e.getTabListDisplayName().getString().trim();
			if (t.regionMatches(true, 0, "Area:", 0, 5)) parts.add(t);
		});
		var sb = mc.level.getScoreboard();
		var obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (obj != null) for (var entry : sb.listPlayerScores(obj)) {
			var team = sb.getPlayersTeam(entry.owner());
			String line = ((team == null ? "" : team.getPlayerPrefix().getString()) + entry.ownerName().getString()
				+ (team == null ? "" : team.getPlayerSuffix().getString())).trim();
			if (line.contains("⏣")) parts.add(line);
		}
		return String.join(" | ", parts);
	}

	private static void readArea(Minecraft mc) {
		String text = readAreaText(mc);
		if (text.equals(areaText)) return;
		areaText = text;
		int m = 0;
		if (MINES.matcher(text).find()) m |= LOC_MINES;
		if (CRYSTAL.matcher(text).find()) m |= LOC_CRYSTAL;
		if (SHAFT.matcher(text).find()) m |= LOC_SHAFT;
		here = m == 0 ? LOC_ELSE : m;
		dirty = true;
		StringBuilder act = new StringBuilder();
		for (Skin s : ALL) if (s.active()) act.append(s.id).append(' ');
		LOGGER.info("area: '{}' -> active: {}", text, act);
	}

	/** Config values from before the sound folder existed. */
	private static String legacySound(String v) {
		return switch (v) {
			case "NOKIA" -> "nokia.ogg";
			case "AMETHYST" -> "amethyst.ogg";
			case "GLASS" -> "glass.ogg";
			case "STONE", "METAL" -> "stone.ogg";
			case "DEFAULT" -> DEFAULT;
			case "SILENT" -> SILENT;
			default -> v;
		};
	}

	/** Back to the built-in settings (before loading a config). */
	static void resetAll() {
		for (Skin s : ALL) s.reset();
		dirty = true;
	}

	/** File names from a config: no folders, so a config can only point inside NokiaQOL/textures and sounds. */
	private static final Pattern SAFE_FILE = Pattern.compile("[A-Za-z0-9 _.\\-]{1,100}");

	private static boolean safe(String s) { return s != null && SAFE_FILE.matcher(s).matches(); }

	static void save(Properties p) {
		for (Skin s : ALL) {
			p.setProperty("skin." + s.id + ".on", Boolean.toString(s.on));
			p.setProperty("skin." + s.id + ".sound", s.sound);
			p.setProperty("skin." + s.id + ".loc", Integer.toString(s.locs));
			for (Slot slot : s.slots) p.setProperty("skin." + s.id + ".tex." + slot.file, slot.chosen);
		}
	}

	static void load(Properties p) {
		for (Skin s : ALL) {
			String on = p.getProperty("skin." + s.id + ".on");
			if (on != null) s.on = Boolean.parseBoolean(on);
			try { String l = p.getProperty("skin." + s.id + ".loc"); if (l != null) s.locs = Integer.parseInt(l) & LOC_ALL; } catch (NumberFormatException ignored) { }
			String snd = p.getProperty("skin." + s.id + ".sound");
			if (snd != null && (snd.equals(DEFAULT) || snd.equals(SILENT) || safe(legacySound(snd)))) s.sound = legacySound(snd);
			for (Slot slot : s.slots) {
				String t = p.getProperty("skin." + s.id + ".tex." + slot.file);
				if (safe(t)) slot.chosen = t;
			}
		}
		dirty = true;
	}
}
