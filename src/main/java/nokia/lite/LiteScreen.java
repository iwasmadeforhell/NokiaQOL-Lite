package nokia.lite;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The whole settings menu. A config bar on top (switch, new, rename, delete, export, import), then one row per ore group:
 * textures on/off, a sound dropdown (files in NokiaQOL/sounds), where it applies (D C S E) and a preview square per texture
 * slot (click one for a dropdown of NokiaQOL/textures).
 */
public class LiteScreen extends Screen {
	private static final Logger LOGGER = LoggerFactory.getLogger("NokiaLite");
	private static final int PREVIEW = 9, PER_LINE = 8, ITEM_H = 12, MAX_ROWS = 10;

	private record Preview(Identifier id, int w, int h) { }

	/** The open dropdown list (null = none). */
	private static final class Dropdown {
		int x, y, w, scroll;
		List<String> items;
		String selected;
		boolean icons;
		Consumer<String> onPick;

		int rows() { return Math.min(items.size(), MAX_ROWS); }
		int height() { return rows() * ITEM_H + 2; }
	}

	private Map<String, Preview> previews;
	private final List<Identifier> registered = new ArrayList<>();
	private Dropdown dd;

	/** 0 = config bar, 1 = naming a new config, 2 = renaming the current one. */
	private int prompt;
	private String promptText = "";
	private EditBox promptBox;
	private boolean confirmDelete;
	private String status = "";
	private int statusColor = 0xFFAAAAAA;

	public LiteScreen() {
		super(Component.literal("NokiaQOL Lite"));
	}

	private void say(String text, boolean ok) {
		status = text;
		statusColor = ok ? 0xFF55FF55 : 0xFFFF5555;
	}

	private static Component onOff(String label, boolean on) {
		return Component.literal(label + ": " + (on ? "§aON" : "§cOFF"));
	}

	private static Component locLabel(String letter, boolean on) {
		return Component.literal((on ? "§a" : "§8") + letter);
	}

	private static boolean special(String item) {
		return item.equals(Skins.DEFAULT) || item.equals(Skins.SILENT) || NokiaLite.ALERT_BUILT_IN.contains(item);
	}

	/** Button text for a sound choice, shortened to fit. */
	private static Component soundLabel(String choice) {
		String t = special(choice) ? choice : Folders.shown(choice);
		if (t.length() > 14) t = t.substring(0, 13) + "…";
		return Component.literal("Sound: " + t);
	}

	private static final String[] EXTRA_TIPS = {
		"Breaking any block that is not in an ore group above (or not in its ticked places)",
		"Picking up experience orbs",
		"Your hits: strong, weak, crit, knockback, sweep and no-damage",
		"The cracking sound while you hit a block, before it breaks (any block)",
		"Note-block chimes (Hypixel plays these a lot); each note keeps its pitch",
		"The windy ambient sound in the Magma Fields (breeze idle)",
	};

	private static Component extraLabel(String label, String choice) {
		String t = special(choice) ? choice : Folders.shown(choice);
		if (t.length() > 9) t = t.substring(0, 8) + "…";
		return Component.literal(label + ": " + t);
	}

	private static String itemLabel(String item) {
		return special(item) ? item : Folders.shown(item);
	}

	// --- previews --------------------------------------------------------------------------------------

	private void loadPreviews() {
		previews = new HashMap<>();
		int n = 0;
		for (String file : Folders.textures()) {
			try (InputStream in = Files.newInputStream(Folders.TEXTURES.resolve(file))) {
				NativeImage img = NativeImage.read(in);
				Identifier id = Identifier.fromNamespaceAndPath("nokiaqol_lite", "preview/" + n++);
				minecraft.getTextureManager().register(id, new DynamicTexture(() -> "NokiaLite preview", img));
				registered.add(id);
				previews.put(file, new Preview(id, img.getWidth(), img.getHeight()));
			} catch (Exception e) {
				LOGGER.warn("cannot preview {}", file, e);
			}
		}
	}

	private void releasePreviews() {
		for (Identifier id : registered) minecraft.getTextureManager().release(id);
		registered.clear();
		previews = null;
	}

	@Override
	public void removed() {
		releasePreviews();
	}

	private Preview previewOf(Skins.Slot slot) {
		Preview p = previews.get(slot.chosen);
		return p != null ? p : previews.get(slot.file + ".png");
	}

	/** A texture preview square; click for the texture dropdown. */
	private final class SlotButton extends AbstractWidget {
		private final Skins.Slot slot;

		SlotButton(int x, int y, Skins.Slot slot) {
			super(x, y, PREVIEW, PREVIEW, Component.empty());
			this.slot = slot;
			tip();
		}

		void tip() {
			setTooltip(Tooltip.create(Component.literal(slot.file + ": " + Folders.shown(slot.chosen))));
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
			Preview p = previewOf(slot);
			if (p != null) {
				int side = Math.min(p.w(), p.h());
				g.blit(RenderPipelines.GUI_TEXTURED, p.id(), getX(), getY(), 0f, 0f, PREVIEW, PREVIEW, side, side, p.w(), p.h());
			} else {
				g.fill(getX(), getY(), getX() + PREVIEW, getY() + PREVIEW, 0xFF555555);
			}
			g.outline(getX() - 1, getY() - 1, PREVIEW + 2, PREVIEW + 2, isHoveredOrFocused() ? 0xFFFFFFFF : 0xFF303030);
		}

		@Override
		public void onClick(MouseButtonEvent e, boolean dbl) {
			openDropdown(getX(), getY(), PREVIEW, 130, Folders.textures(), slot.chosen, true, file -> {
				slot.chosen = file;
				Skins.dirty = true;
				NokiaLite.save();
				tip();
			});
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput out) { }
	}

	// --- dropdown --------------------------------------------------------------------------------------

	private void openDropdown(int ax, int ay, int ah, int minW, List<String> items, String selected, boolean icons, Consumer<String> onPick) {
		Dropdown d = new Dropdown();
		d.items = items;
		d.selected = selected;
		d.icons = icons;
		d.onPick = onPick;
		int w = minW;
		for (String s : items) w = Math.max(w, font.width(itemLabel(s)) + (icons ? 20 : 10));
		d.w = Math.min(w, 220);
		d.x = Math.max(2, Math.min(ax, width - d.w - 2));
		d.y = ay + ah + 1;
		if (d.y + d.height() > height - 2) d.y = Math.max(2, ay - d.height() - 1);
		int sel = items.indexOf(selected);
		d.scroll = Math.max(0, Math.min(Math.max(0, items.size() - d.rows()), sel - d.rows() / 2));
		dd = d;
	}

	private boolean inside(Dropdown d, double mx, double my) {
		return mx >= d.x && mx < d.x + d.w && my >= d.y && my < d.y + d.height();
	}

	private int itemAt(Dropdown d, double my) {
		int i = d.scroll + (int) ((my - d.y - 1) / ITEM_H);
		return i >= 0 && i < d.items.size() ? i : -1;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
		super.extractRenderState(g, mx, my, pt);
		if (!status.isEmpty()) g.text(font, status, width / 2 - 238, height - 11, statusColor);
		Dropdown d = dd;
		if (d == null) return;
		g.fill(d.x, d.y, d.x + d.w, d.y + d.height(), 0xF0101010);
		g.outline(d.x, d.y, d.w, d.height(), 0xFFAAAAAA);
		for (int r = 0; r < d.rows(); r++) {
			int i = d.scroll + r;
			String item = d.items.get(i);
			int iy = d.y + 1 + r * ITEM_H;
			if (inside(d, mx, my) && itemAt(d, my) == i) g.fill(d.x + 1, iy, d.x + d.w - 1, iy + ITEM_H, 0xFF404040);
			int tx = d.x + 4;
			if (d.icons) {
				Preview p = previews.get(item);
				if (p != null) {
					int side = Math.min(p.w(), p.h());
					g.blit(RenderPipelines.GUI_TEXTURED, p.id(), d.x + 2, iy + 1, 0f, 0f, 10, 10, side, side, p.w(), p.h());
				}
				tx = d.x + 15;
			}
			g.text(font, itemLabel(item), tx, iy + 2, item.equals(d.selected) ? 0xFF55FF55 : 0xFFFFFFFF);
		}
		if (d.items.size() > d.rows()) {
			int trackH = d.height() - 2;
			int barH = Math.max(6, trackH * d.rows() / d.items.size());
			int barY = d.y + 1 + (trackH - barH) * d.scroll / (d.items.size() - d.rows());
			g.fill(d.x + d.w - 3, barY, d.x + d.w - 1, barY + barH, 0xFF888888);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
		Dropdown d = dd;
		if (d == null) return super.mouseClicked(e, dbl);
		dd = null; // any click closes it; one on an entry also picks it
		if (inside(d, e.x(), e.y())) {
			int i = itemAt(d, e.y());
			if (i >= 0) d.onPick.accept(d.items.get(i));
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		Dropdown d = dd;
		if (d == null) return super.mouseScrolled(mx, my, sx, sy);
		d.scroll = Math.max(0, Math.min(Math.max(0, d.items.size() - d.rows()), d.scroll - (int) Math.signum(sy)));
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (dd != null && e.key() == GLFW.GLFW_KEY_ESCAPE) { dd = null; return true; }
		if (prompt != 0) {
			if (e.key() == GLFW.GLFW_KEY_ESCAPE) { prompt = 0; rebuildWidgets(); return true; }
			if (e.key() == GLFW.GLFW_KEY_ENTER || e.key() == GLFW.GLFW_KEY_KP_ENTER) { confirmPrompt(); return true; }
		}
		return super.keyPressed(e);
	}

	// --- configs ---------------------------------------------------------------------------------------

	private void confirmPrompt() {
		String text = promptBox.getValue();
		if (prompt == 1) {
			say("Created config " + Profiles.create(text), true);
		} else if (Profiles.rename(text)) {
			say("Renamed to " + Profiles.active, true);
		} else {
			say("That name is empty or already used", false);
		}
		prompt = 0;
		rebuildWidgets();
	}

	private void export() {
		try {
			Path zip = Profiles.export();
			Util.getPlatform().openPath(zip.getParent());
			say("Exported " + zip.getFileName() + " (NokiaQOL/exports)", true);
		} catch (IOException e) {
			LOGGER.warn("export failed", e);
			say("Export failed: " + e.getMessage(), false);
		}
	}

	/** Opens the system file dialog (off the game thread) and imports the zip you pick. */
	private void pickImport() {
		Path start = Files.isDirectory(Profiles.EXPORTS) ? Profiles.EXPORTS : Folders.ROOT;
		Thread t = new Thread(() -> {
			String path;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				PointerBuffer filters = stack.mallocPointer(1);
				filters.put(stack.UTF8("*.zip")).flip();
				path = TinyFileDialogs.tinyfd_openFileDialog("Import a NokiaQOL config", start + File.separator, filters, "NokiaQOL config (*.zip)", false);
			}
			if (path != null) minecraft.execute(() -> doImport(Path.of(path)));
		}, "NokiaLite-import");
		t.setDaemon(true);
		t.start();
	}

	private void doImport(Path zip) {
		try {
			say("Imported as " + Profiles.importZip(zip), true);
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("import failed", e);
			say("Import failed: " + e.getMessage(), false);
		}
		if (minecraft.gui.screen() == this) {
			releasePreviews(); // the zip may have brought new textures
			rebuildWidgets();
		}
	}

	// --- layout ----------------------------------------------------------------------------------------

	private List<String> soundChoices(boolean alert) {
		List<String> items = new ArrayList<>();
		if (alert) items.addAll(NokiaLite.ALERT_BUILT_IN);
		else { items.add(Skins.DEFAULT); items.add(Skins.SILENT); }
		items.addAll(Folders.sounds());
		return items;
	}

	private void buildConfigBar(int x, int y) {
		String shown = Profiles.active.length() > 14 ? Profiles.active.substring(0, 13) + "…" : Profiles.active;
		addRenderableWidget(Button.builder(Component.literal("Config: " + shown), b -> openDropdown(x, y, 20, 140, Profiles.list(), Profiles.active, false, name -> {
			if (name.equals(Profiles.active)) return;
			Profiles.switchTo(name);
			say("Loaded config " + name, true);
			rebuildWidgets();
		})).bounds(x, y, 140, 20).build());
		addRenderableWidget(Button.builder(Component.literal("New"), b -> { prompt = 1; promptText = Profiles.unique("Config"); rebuildWidgets(); })
			.bounds(x + 144, y, 38, 20).tooltip(Tooltip.create(Component.literal("New config, starting as a copy of this one"))).build());
		addRenderableWidget(Button.builder(Component.literal("Rename"), b -> { prompt = 2; promptText = Profiles.active; rebuildWidgets(); })
			.bounds(x + 186, y, 52, 20).build());
		Button del = Button.builder(Component.literal("Delete"), b -> {
			if (!confirmDelete) { confirmDelete = true; b.setMessage(Component.literal("§cSure?")); return; }
			confirmDelete = false;
			String gone = Profiles.active;
			if (Profiles.delete()) say("Deleted config " + gone, true);
			rebuildWidgets();
		}).bounds(x + 242, y, 46, 20).tooltip(Tooltip.create(Component.literal("Click twice to delete this config"))).build();
		del.active = Profiles.list().size() > 1;
		addRenderableWidget(del);
		addRenderableWidget(Button.builder(Component.literal("Export"), b -> export()).bounds(x + 296, y, 52, 20)
			.tooltip(Tooltip.create(Component.literal("Save this config and its textures and sounds as a zip in NokiaQOL/exports"))).build());
		addRenderableWidget(Button.builder(Component.literal("Import"), b -> pickImport()).bounds(x + 352, y, 52, 20)
			.tooltip(Tooltip.create(Component.literal("Pick an exported zip"))).build());
	}

	private void buildPrompt(int x, int y) {
		addRenderableWidget(new StringWidget(x, y + 6, 50, 9, Component.literal(prompt == 1 ? "New:" : "Rename:"), font));
		EditBox box = new EditBox(font, x + 52, y, 180, 20, Component.literal("Config name"));
		box.setMaxLength(24);
		box.setValue(promptText);
		box.setResponder(v -> {
			String clean = v.replaceAll("[^A-Za-z0-9 _-]", "");
			if (!clean.equals(v)) box.setValue(clean);
		});
		promptBox = box;
		addRenderableWidget(box);
		setInitialFocus(box);
		addRenderableWidget(Button.builder(Component.literal("OK"), b -> confirmPrompt()).bounds(x + 236, y, 50, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> { prompt = 0; rebuildWidgets(); }).bounds(x + 290, y, 60, 20).build());
	}

	@Override
	protected void init() {
		if (previews == null) loadPreviews();
		dd = null;
		confirmDelete = false;
		int extraLines = (Skins.EXTRA.length + 3) / 4;
		int rows = Skins.ALL.size() + 4 + extraLines;
		int rowH = Math.max(20, Math.min(24, (height - 96) / rows));
		int bh = rowH - 4;
		int x = width / 2 - 238;
		addRenderableWidget(new StringWidget(x, 4, 476, 9, title, font));
		addRenderableWidget(new StringWidget(x, 15, 476, 9, Component.literal("§7D = Dwarven Mines/Tunnels  C = Crystal Hollows  S = Mineshafts  E = elsewhere  P = particles"), font));
		int barY = 27;
		if (prompt == 0) buildConfigBar(x, barY);
		else buildPrompt(x, barY);
		int y = barY + 28;

		for (Skins.Skin s : Skins.ALL) {
			addRenderableWidget(new StringWidget(x, y + 6, 76, 9, Component.literal(s.label), font));
			addRenderableWidget(Button.builder(onOff("Textures", s.on), b -> {
				s.on = !s.on;
				Skins.dirty = true;
				NokiaLite.save();
				b.setMessage(onOff("Textures", s.on));
			}).bounds(x + 78, y, 84, bh).build());
			int sx = x + 166, sy = y;
			addRenderableWidget(Button.builder(soundLabel(s.sound), b -> openDropdown(sx, sy, bh, 120, soundChoices(false), s.sound, false, pick -> {
				s.sound = pick;
				NokiaLite.save();
				b.setMessage(soundLabel(pick));
				if (!pick.equals(Skins.DEFAULT) && !pick.equals(Skins.SILENT)) Sounds.play(pick, Sounds.BREAK_GAIN * Sounds.volume(SoundSource.BLOCKS));
			})).bounds(sx, sy, 120, bh).build());
			for (int i = 0; i < 4; i++) {
				int bit = 1 << i;
				String letter = Skins.LOC_LETTER[i];
				addRenderableWidget(Button.builder(locLabel(letter, (s.locs & bit) != 0), b -> {
					s.locs ^= bit;
					Skins.dirty = true;
					NokiaLite.save();
					b.setMessage(locLabel(letter, (s.locs & bit) != 0));
				}).bounds(x + 290 + i * 20, y, 20, bh).tooltip(Tooltip.create(Component.literal(Skins.LOC_NAME[i]))).build());
			}
			addRenderableWidget(Button.builder(locLabel("P", s.particles), b -> {
				s.particles = !s.particles;
				NokiaLite.save();
				b.setMessage(locLabel("P", s.particles));
			}).bounds(x + 372, y, 20, bh).tooltip(Tooltip.create(Component.literal("Particles when you hit and break these blocks (grey = hidden)"))).build());
			int lines = (s.slots.size() + PER_LINE - 1) / PER_LINE;
			int blockH = lines * (PREVIEW + 1) - 1;
			int py = y + (bh - blockH) / 2;
			for (int i = 0; i < s.slots.size(); i++) {
				addRenderableWidget(new SlotButton(x + 396 + (i % PER_LINE) * (PREVIEW + 1), py + (i / PER_LINE) * (PREVIEW + 1), s.slots.get(i)));
			}
			y += rowH;
		}

		y += 4;
		addRenderableWidget(new StringWidget(x, y + 6, 76, 9, Component.literal("Other sounds"), font));
		for (int i = 0; i < Skins.EXTRA.length; i++) {
			String key = Skins.EXTRA[i][0], label = Skins.EXTRA[i][1];
			int ex = x + 78 + (i % 4) * 96, ey = y + (i / 4) * rowH;
			addRenderableWidget(Button.builder(extraLabel(label, Skins.extra.get(key)), b -> openDropdown(ex, ey, bh, 110, soundChoices(false), Skins.extra.get(key), false, pick -> {
				Skins.extra.put(key, pick);
				NokiaLite.save();
				b.setMessage(extraLabel(label, pick));
				Skins.previewExtra(key);
			})).bounds(ex, ey, 92, bh).tooltip(Tooltip.create(Component.literal(EXTRA_TIPS[i]))).build());
		}
		y += rowH * extraLines;
		addRenderableWidget(new StringWidget(x, y + 6, 90, 9, Component.literal("Ability alert"), font));
		addRenderableWidget(Button.builder(onOff("Alert", NokiaLite.alertOn), b -> {
			NokiaLite.alertOn = !NokiaLite.alertOn;
			NokiaLite.save();
			b.setMessage(onOff("Alert", NokiaLite.alertOn));
		}).bounds(x + 78, y, 84, bh).build());
		int ay = y;
		addRenderableWidget(Button.builder(soundLabel(NokiaLite.alertSound), b -> openDropdown(x + 166, ay, bh, 120, soundChoices(true), NokiaLite.alertSound, false, pick -> {
			NokiaLite.alertSound = pick;
			NokiaLite.save();
			b.setMessage(soundLabel(pick));
			NokiaLite.playAlertSound(minecraft);
		})).bounds(x + 166, y, 120, bh).build());
		y += rowH;
		addRenderableWidget(Button.builder(onOff("Title", NokiaLite.alertTitle), b -> {
			NokiaLite.alertTitle = !NokiaLite.alertTitle;
			NokiaLite.save();
			b.setMessage(onOff("Title", NokiaLite.alertTitle));
		}).bounds(x + 78, y, 84, bh).build());
		addRenderableWidget(Button.builder(Component.literal("Open folder"), b -> Folders.open()).bounds(x + 310, y, 100, bh)
			.tooltip(Tooltip.create(Component.literal("NokiaQOL folder: put your own .png textures and .ogg/.mp3/.wav sounds in it"))).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(x + 414, y, 62, bh).build());
	}
}
