package nokia.lite;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The NokiaQOL folder in the game directory: textures/*.png and sounds/*.(ogg|mp3|wav), filled with the built-in ones on first run. */
final class Folders {
	private static final Logger LOGGER = LoggerFactory.getLogger("NokiaLite");
	static final Path ROOT = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("NokiaQOL");
	static final Path TEXTURES = ROOT.resolve("textures");
	static final Path SOUNDS = ROOT.resolve("sounds");

	private static boolean exported;

	/** Vanilla sounds copied next to Nokia's own: file name -> resource inside the game. */
	private static final String[][] VANILLA_SOUNDS = {
		{"amethyst.ogg", "sounds/block/amethyst/break1.ogg"},
		{"glass.ogg", "sounds/random/glass1.ogg"},
		{"stone.ogg", "sounds/dig/stone1.ogg"},
	};

	/** Fills the folders once (only when a folder does not exist yet, so files you delete stay deleted). */
	static void exportDefaults(Minecraft mc) {
		if (exported) return;
		exported = true;
		LOGGER.info("NokiaQOL folder: {}", ROOT);
		try {
			if (!Files.exists(TEXTURES)) {
				Files.createDirectories(TEXTURES);
				for (Skins.Skin s : Skins.ALL) for (Skins.Slot slot : s.slots) copyBuiltIn("textures/" + slot.file + ".png", TEXTURES.resolve(slot.file + ".png"));
			}
			if (!Files.exists(SOUNDS)) {
				Files.createDirectories(SOUNDS);
				copyBuiltIn("sounds/nokia.ogg", SOUNDS.resolve("nokia.ogg"));
				for (String[] v : VANILLA_SOUNDS) {
					try (InputStream in = mc.getResourceManager().getResource(Identifier.withDefaultNamespace(v[1])).orElseThrow().open()) {
						Files.copy(in, SOUNDS.resolve(v[0]));
					} catch (Exception e) {
						LOGGER.warn("could not export vanilla sound {}", v[1], e);
					}
				}
			}
		} catch (IOException e) {
			LOGGER.warn("could not create {}", ROOT, e);
		}
	}

	private static void copyBuiltIn(String name, Path to) throws IOException {
		try (InputStream in = Folders.class.getResourceAsStream("/nokiaqol_lite_defaults/" + name)) {
			if (in == null) { LOGGER.warn("missing built-in {}", name); return; }
			Files.copy(in, to);
		}
	}

	/** File names in {@code dir} with one of the extensions, alphabetical (case-insensitive). */
	static List<String> list(Path dir, String... exts) {
		List<String> out = new ArrayList<>();
		if (!Files.isDirectory(dir)) return out;
		try (Stream<Path> s = Files.list(dir)) {
			s.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).forEach(n -> {
				String l = n.toLowerCase();
				for (String e : exts) if (l.endsWith(e)) { out.add(n); return; }
			});
		} catch (IOException e) {
			LOGGER.warn("could not list {}", dir, e);
		}
		out.sort(Comparator.comparing(String::toLowerCase));
		return out;
	}

	static List<String> textures() { return list(TEXTURES, ".png"); }

	static List<String> sounds() { return list(SOUNDS, ".ogg", ".mp3", ".wav"); }

	static void open() {
		try { Files.createDirectories(ROOT); } catch (IOException ignored) { }
		Util.getPlatform().openPath(ROOT);
	}

	/** "nokia.ogg" -> "nokia". */
	static String shown(String file) {
		int i = file.lastIndexOf('.');
		return i > 0 ? file.substring(0, i) : file;
	}
}
