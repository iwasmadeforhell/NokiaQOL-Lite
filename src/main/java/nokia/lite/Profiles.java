package nokia.lite;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Named configs: each one is a file in NokiaQOL/configs holding every skin setting (switches, sounds, places, textures).
 * A config can be exported to a zip with the textures and sounds it uses, and imported from one.
 */
final class Profiles {
	private static final Logger LOGGER = LoggerFactory.getLogger("NokiaLite");
	static final Path DIR = Folders.ROOT.resolve("configs");
	static final Path EXPORTS = Folders.ROOT.resolve("exports");
	static volatile String active = "default";

	private static final Pattern ZIP_TEXTURE = Pattern.compile("textures/([A-Za-z0-9 _.\\-]{1,100}\\.png)");
	private static final Pattern ZIP_SOUND = Pattern.compile("sounds/([A-Za-z0-9 _.\\-]{1,100}\\.(?:ogg|mp3|wav))");
	private static final int MAX_ENTRY = 8 << 20, MAX_TOTAL = 64 << 20;

	/** Letters, digits, space, underscore and dash, at most 24 characters. */
	static String sanitize(String s) {
		String t = s.replaceAll("[^A-Za-z0-9 _-]", "").trim();
		return t.length() > 24 ? t.substring(0, 24).trim() : t;
	}

	static Path file(String name) { return DIR.resolve(name + ".properties"); }

	static List<String> list() {
		List<String> out = new ArrayList<>();
		for (String f : Folders.list(DIR, ".properties")) out.add(f.substring(0, f.length() - ".properties".length()));
		if (out.stream().noneMatch(active::equalsIgnoreCase)) out.add(active);
		out.sort(Comparator.comparing(String::toLowerCase));
		return out;
	}

	static boolean exists(String name) { return list().stream().anyMatch(name::equalsIgnoreCase); }

	/** {@code base} made unique among the configs ("Config", "Config 2", ...). */
	static String unique(String base) {
		String b = sanitize(base);
		if (b.isEmpty()) b = "Config";
		if (b.length() > 20) b = b.substring(0, 20).trim();
		String n = b;
		for (int i = 2; exists(n); i++) n = b + " " + i;
		return n;
	}

	static Properties read(String name) {
		Path p = file(name);
		if (!Files.isRegularFile(p)) return null;
		Properties props = new Properties();
		try (InputStream in = Files.newInputStream(p)) {
			props.load(in);
			return props;
		} catch (IOException e) {
			LOGGER.warn("cannot read config {}", name, e);
			return null;
		}
	}

	static void saveActive() {
		Properties p = new Properties();
		Skins.save(p);
		try {
			Files.createDirectories(DIR);
			try (OutputStream out = Files.newOutputStream(file(active))) { p.store(out, "NokiaQOL Lite config"); }
		} catch (IOException e) {
			LOGGER.warn("cannot save config {}", active, e);
		}
	}

	/** Loads the active config from its file at startup. */
	static void loadActive(Properties legacyMain) {
		Properties p = read(active);
		Skins.load(p != null ? p : legacyMain);
	}

	static void switchTo(String name) {
		saveActive();
		Skins.resetAll();
		Properties p = read(name);
		if (p != null) Skins.load(p);
		active = name;
		Skins.dirty = true;
		NokiaLite.save();
	}

	/** A new config that starts as a copy of the current settings. Returns its name. */
	static String create(String name) {
		saveActive();
		active = unique(name);
		saveActive();
		NokiaLite.save();
		return active;
	}

	static boolean rename(String to) {
		String n = sanitize(to);
		if (n.isEmpty() || (!n.equalsIgnoreCase(active) && exists(n))) return false;
		saveActive();
		try {
			Files.move(file(active), file(n), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			LOGGER.warn("cannot rename config", e);
			return false;
		}
		active = n;
		NokiaLite.save();
		return true;
	}

	/** Deletes the active config and loads another one. False if it is the only one. */
	static boolean delete() {
		String gone = active;
		List<String> rest = new ArrayList<>(list());
		rest.removeIf(gone::equalsIgnoreCase);
		if (rest.isEmpty()) return false;
		try { Files.deleteIfExists(file(gone)); } catch (IOException e) { LOGGER.warn("cannot delete config", e); }
		active = rest.get(0);
		Skins.resetAll();
		Properties p = read(active);
		if (p != null) Skins.load(p);
		Skins.dirty = true;
		NokiaLite.save();
		return true;
	}

	// --- zip export / import ---------------------------------------------------------------------------

	/** Writes the active config and every texture and sound it uses (renamed [name]-file) to exports/[name].zip. */
	static Path export() throws IOException {
		saveActive();
		String prefix = active.replace(' ', '_') + "-";
		Properties out = new Properties();
		out.setProperty("name", active);
		Map<String, Path> files = new LinkedHashMap<>(); // zip entry -> source
		for (Skins.Skin s : Skins.ALL) {
			out.setProperty("skin." + s.id + ".on", Boolean.toString(s.on));
			out.setProperty("skin." + s.id + ".particles", Boolean.toString(s.particles));
			out.setProperty("skin." + s.id + ".loc", Integer.toString(s.locs));
			String snd = s.sound;
			if (!snd.equals(Skins.DEFAULT) && !snd.equals(Skins.SILENT) && Sounds.exists(snd)) {
				files.put("sounds/" + prefix + snd, Folders.SOUNDS.resolve(snd));
				snd = prefix + snd;
			}
			out.setProperty("skin." + s.id + ".sound", snd);
			for (Skins.Slot slot : s.slots) {
				Path p = slot.path();
				if (p == null) continue;
				String name = prefix + p.getFileName();
				files.put("textures/" + name, p);
				out.setProperty("skin." + s.id + ".tex." + slot.file, name);
			}
		}
		for (String[] e : Skins.EXTRA) {
			String snd = Skins.extra.get(e[0]);
			if (!snd.equals(Skins.DEFAULT) && !snd.equals(Skins.SILENT) && Sounds.exists(snd)) {
				files.put("sounds/" + prefix + snd, Folders.SOUNDS.resolve(snd));
				snd = prefix + snd;
			}
			out.setProperty("sound." + e[0], snd);
		}
		Files.createDirectories(EXPORTS);
		Path zip = EXPORTS.resolve(active.replace(' ', '_') + ".zip");
		try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
			z.putNextEntry(new ZipEntry("config.properties"));
			out.store(z, "NokiaQOL Lite config");
			z.closeEntry();
			for (Map.Entry<String, Path> e : files.entrySet()) {
				z.putNextEntry(new ZipEntry(e.getKey()));
				Files.copy(e.getValue(), z);
				z.closeEntry();
			}
		}
		return zip;
	}

	private static byte[] readLimited(InputStream in) throws IOException {
		byte[] data = in.readNBytes(MAX_ENTRY + 1);
		if (data.length > MAX_ENTRY) throw new IOException("zip entry too large");
		return data;
	}

	/** Imports an exported zip as a new config (existing textures and sounds are never overwritten) and switches to it. Returns its name. */
	static String importZip(Path zip) throws IOException {
		Properties cfg = null;
		long total = 0;
		Files.createDirectories(Folders.TEXTURES);
		Files.createDirectories(Folders.SOUNDS);
		try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
			for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
				if (e.isDirectory()) continue;
				String n = e.getName();
				var tex = ZIP_TEXTURE.matcher(n);
				var snd = ZIP_SOUND.matcher(n);
				boolean isCfg = n.equals("config.properties");
				if (!isCfg && !tex.matches() && !snd.matches()) continue; // anything else in the zip is ignored
				byte[] data = readLimited(z);
				total += data.length;
				if (total > MAX_TOTAL) throw new IOException("zip too large");
				if (isCfg) {
					cfg = new Properties();
					cfg.load(new ByteArrayInputStream(data));
				} else {
					Path to = (tex.matches() ? Folders.TEXTURES : Folders.SOUNDS).resolve((tex.matches() ? tex : snd).group(1));
					if (!Files.exists(to)) Files.write(to, data);
				}
			}
		}
		if (cfg == null) throw new IOException("not a NokiaQOL config: no config.properties inside");
		String fallback = zip.getFileName().toString().replaceAll("\\.zip$", "");
		String name = unique(sanitize(cfg.getProperty("name", fallback)));
		saveActive();
		active = name;
		Skins.resetAll();
		Skins.load(cfg);
		Skins.dirty = true;
		saveActive();
		NokiaLite.save();
		return name;
	}
}
