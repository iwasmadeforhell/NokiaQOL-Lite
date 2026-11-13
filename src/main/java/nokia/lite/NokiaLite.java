package nokia.lite;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.Command;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point: ore skins (Skins), the pickaxe ability alert, config and the settings screen (Right Ctrl or /nokialite). */
public class NokiaLite implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("NokiaLite");
	private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("nokiaqol-lite.properties");

	// Pickaxe ability alert: "Maniac Miner is now available!" in chat -> sound + title.
	private static final Pattern READY = Pattern.compile("^([A-Za-z' ]{2,40}) is now available!$");
	private static final Pattern FORMAT = Pattern.compile("§.");

	/** Alert sounds that are not files. */
	static final List<String> ALERT_BUILT_IN = List.of("Ding", "Pling", "Level up");

	public static volatile boolean alertOn = true;
	public static volatile boolean alertTitle = true;
	/** One of ALERT_BUILT_IN or a file name in NokiaQOL/sounds. */
	public static volatile String alertSound = "Ding";

	private static boolean openRequested;
	private static boolean ctrlWasDown;
	private static long lastAlertMs;

	@Override
	public void onInitializeClient() {
		load();
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
			ClientCommands.literal("nokialite")
				.executes(c -> { openRequested = true; return Command.SINGLE_SUCCESS; })
				.then(ClientCommands.literal("sounds").executes(c -> {
					Skins.logSounds = !Skins.logSounds;
					c.getSource().sendFeedback(Component.literal("[NokiaLite] sound names in chat: " + (Skins.logSounds ? "on (run it again to stop)" : "off")));
					return Command.SINGLE_SUCCESS;
				}))
				.then(ClientCommands.literal("debug").executes(c -> {
					c.getSource().sendFeedback(Component.literal("[NokiaLite] area: '" + Skins.readAreaText(Minecraft.getInstance()) + "'"));
					for (Skins.Skin s : Skins.ALL) c.getSource().sendFeedback(Component.literal("[NokiaLite] " + s.id + ": " + (s.on ? (s.active() ? "active" : "waiting for area") : "off")));
					c.getSource().sendFeedback(Component.literal("[NokiaLite] atlas: " + AtlasPatcher.summary()));
					return Command.SINGLE_SUCCESS;
				}))));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			Skins.tick(mc);
			boolean down = InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL);
			if (down && !ctrlWasDown) openRequested = true;
			ctrlWasDown = down;
			if (openRequested && mc.gui.screen() == null) { openRequested = false; mc.gui.setScreen(new LiteScreen()); }
		});
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay || !alertOn) return;
			Matcher m = READY.matcher(FORMAT.matcher(message.getString()).replaceAll("").trim());
			long now = System.currentTimeMillis();
			if (!m.matches() || now - lastAlertMs < 1000) return;
			lastAlertMs = now;
			fireAlert(m.group(1).trim());
		});
	}

	static void fireAlert(String ability) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			playAlertSound(mc);
			if (alertTitle) {
				mc.gui.hud.setTimes(5, 40, 15);
				mc.gui.hud.setSubtitle(Component.literal("§7" + ability + " is now available"));
				mc.gui.hud.setTitle(Component.literal("§a§lABILITY READY"));
			}
		});
	}

	/** Also used when you pick a sound in the menu. */
	static void playAlertSound(Minecraft mc) {
		SoundEvent built = switch (alertSound) {
			case "Ding" -> SoundEvents.EXPERIENCE_ORB_PICKUP;
			case "Pling" -> SoundEvents.NOTE_BLOCK_PLING.value();
			case "Level up" -> SoundEvents.PLAYER_LEVELUP;
			default -> null;
		};
		if (built != null) mc.getSoundManager().play(SimpleSoundInstance.forUI(built, 1f, 1f));
		else if (Sounds.exists(alertSound)) Sounds.play(alertSound, Sounds.volume(SoundSource.MASTER));
	}

	static void load() {
		if (!Files.exists(CONFIG)) return;
		Properties p = new Properties();
		try (InputStream in = Files.newInputStream(CONFIG)) {
			p.load(in);
			Profiles.active = Profiles.sanitize(p.getProperty("config.active", "default"));
			if (Profiles.active.isEmpty()) Profiles.active = "default";
			Profiles.loadActive(p); // the config file, or the skin settings an older version kept in this file
			Folders.installed = Boolean.parseBoolean(p.getProperty("defaults.installed", "false"));
			alertOn = Boolean.parseBoolean(p.getProperty("alert.on", "true"));
			alertTitle = Boolean.parseBoolean(p.getProperty("alert.title", "true"));
			String snd = p.getProperty("alert.sound", "Ding");
			alertSound = switch (snd) { case "DING" -> "Ding"; case "PLING" -> "Pling"; case "LEVEL_UP" -> "Level up"; default -> snd; };
		} catch (IOException e) {
			LOGGER.warn("failed to load config", e);
		}
	}

	static void save() {
		Profiles.saveActive();
		Properties p = new Properties();
		p.setProperty("config.active", Profiles.active);
		p.setProperty("defaults.installed", Boolean.toString(Folders.installed));
		p.setProperty("alert.on", Boolean.toString(alertOn));
		p.setProperty("alert.title", Boolean.toString(alertTitle));
		p.setProperty("alert.sound", alertSound);
		try (OutputStream out = Files.newOutputStream(CONFIG)) {
			p.store(out, "NokiaQOL Lite");
		} catch (IOException e) {
			LOGGER.warn("failed to save config", e);
		}
	}
}
