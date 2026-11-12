package nokia.lite;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.SampleBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Plays the files in NokiaQOL/sounds (ogg, mp3, wav) on their own audio line, scaled by the game's volume sliders. */
final class Sounds {
	private static final Logger LOGGER = LoggerFactory.getLogger("NokiaLite/Sound");

	/** shortcut: one fixed loudness for every custom break sound, add a slider if it is too quiet or loud. */
	static final float BREAK_GAIN = 0.3f;

	private record Pcm(byte[] bytes, AudioFormat format, long stamp) { }

	private static final Map<Path, Pcm> CACHE = new HashMap<>();

	/** Master * source volume from the game's sound options. */
	static float volume(SoundSource source) {
		return Minecraft.getInstance().options.getFinalSoundSourceVolume(source);
	}

	/** True if {@code file} exists in the sounds folder. */
	static boolean exists(String file) { return Files.isRegularFile(Folders.SOUNDS.resolve(file)); }

	static void play(String file, float gain) {
		Path path = Folders.SOUNDS.resolve(file);
		Thread t = new Thread(() -> {
			try {
				Pcm pcm = load(path);
				if (pcm == null) return;
				byte[] data = scale(pcm.bytes(), gain);
				SourceDataLine line = AudioSystem.getSourceDataLine(pcm.format());
				line.open(pcm.format());
				line.start();
				line.write(data, 0, data.length);
				line.drain();
				line.close();
			} catch (Exception e) {
				LOGGER.warn("could not play {}", path, e);
			}
		}, "NokiaLite-sound");
		t.setDaemon(true);
		t.start();
	}

	/** 16-bit little-endian samples times {@code gain}. */
	private static byte[] scale(byte[] src, float gain) {
		float g = Math.max(0f, Math.min(2f, gain));
		byte[] out = src.clone();
		for (int i = 0; i + 1 < out.length; i += 2) {
			int s = (short) ((out[i + 1] << 8) | (out[i] & 0xFF));
			int n = Math.max(-32768, Math.min(32767, (int) (s * g)));
			out[i] = (byte) n;
			out[i + 1] = (byte) (n >> 8);
		}
		return out;
	}

	private static synchronized Pcm load(Path path) throws Exception {
		if (!Files.isRegularFile(path)) { LOGGER.warn("sound file not found: {}", path); return null; }
		long stamp = Files.getLastModifiedTime(path).toMillis();
		Pcm cached = CACHE.get(path);
		if (cached != null && cached.stamp() == stamp) return cached;
		String n = path.getFileName().toString().toLowerCase();
		Pcm pcm = n.endsWith(".ogg") ? decodeOgg(path, stamp) : n.endsWith(".mp3") ? decodeMp3(path, stamp) : decodeJava(path, stamp);
		CACHE.put(path, pcm);
		return pcm;
	}

	private static Pcm decodeOgg(Path path, long stamp) throws Exception {
		try (InputStream in = new BufferedInputStream(Files.newInputStream(path)); JOrbisAudioStream ogg = new JOrbisAudioStream(in)) {
			ByteBuffer buf = ogg.readAll();
			byte[] bytes = new byte[buf.remaining()];
			buf.get(bytes);
			return new Pcm(bytes, ogg.getFormat(), stamp);
		}
	}

	private static Pcm decodeMp3(Path path, long stamp) throws Exception {
		try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
			Bitstream bs = new Bitstream(in);
			Decoder decoder = new Decoder();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			int rate = 44100, channels = 2;
			for (var h = bs.readFrame(); h != null; h = bs.readFrame()) {
				SampleBuffer buf = (SampleBuffer) decoder.decodeFrame(h, bs);
				rate = buf.getSampleFrequency();
				channels = buf.getChannelCount();
				short[] s = buf.getBuffer();
				for (int i = 0; i < buf.getBufferLength(); i++) { out.write(s[i] & 0xFF); out.write((s[i] >> 8) & 0xFF); }
				bs.closeFrame();
			}
			return new Pcm(out.toByteArray(), new AudioFormat(rate, 16, channels, true, false), stamp);
		}
	}

	private static Pcm decodeJava(Path path, long stamp) throws Exception {
		try (var src = AudioSystem.getAudioInputStream(path.toFile())) {
			AudioFormat base = src.getFormat();
			AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, base.getSampleRate(), 16, base.getChannels(), base.getChannels() * 2, base.getSampleRate(), false);
			try (var pcm = AudioSystem.getAudioInputStream(target, src)) {
				return new Pcm(pcm.readAllBytes(), target, stamp);
			}
		}
	}
}
