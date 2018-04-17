package de.piegames.voicepi.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioFormat.Encoding;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import org.apache.commons.io.input.CloseShieldInputStream;
import org.greenrobot.essentials.io.CircularByteBuffer;
import org.jaudiolibs.jnajack.Jack;
import org.jaudiolibs.jnajack.JackBufferSizeCallback;
import org.jaudiolibs.jnajack.JackClient;
import org.jaudiolibs.jnajack.JackException;
import org.jaudiolibs.jnajack.JackOptions;
import org.jaudiolibs.jnajack.JackPort;
import org.jaudiolibs.jnajack.JackPortFlags;
import org.jaudiolibs.jnajack.JackPortType;
import org.jaudiolibs.jnajack.JackProcessCallback;
import org.jaudiolibs.jnajack.JackSampleRateCallback;
import org.jaudiolibs.jnajack.JackStatus;
import com.google.api.client.util.IOUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

public abstract class Audio {

	/** The default format. It will be used for all audio processing and if possible for audio recording. */
	public static final AudioFormat	FORMAT	= new AudioFormat(16000, 16, 1, true, false);

	protected AudioFormat			format;
	protected VolumeSpeechDetector	volume;

	public Audio(JsonObject config) {
		this.format = new AudioFormat(
				Optional.ofNullable(config.getAsJsonPrimitive("sample-rate")).map(JsonPrimitive::getAsFloat).orElse(FORMAT.getSampleRate()),
				Optional.ofNullable(config.getAsJsonPrimitive("sample-size")).map(JsonPrimitive::getAsInt).orElse(FORMAT.getSampleSizeInBits()),
				Optional.ofNullable(config.getAsJsonPrimitive("channels")).map(JsonPrimitive::getAsInt).orElse(FORMAT.getChannels()),
				Optional.ofNullable(config.getAsJsonPrimitive("signed")).map(JsonPrimitive::getAsBoolean).orElse(true),
				Optional.ofNullable(config.getAsJsonPrimitive("big-endian")).map(JsonPrimitive::getAsBoolean).orElse(false));
		// TODO configure
		this.volume = new VolumeSpeechDetector(300, 300);
	}

	/**
	 * This will start listening until the returned {@code AudioInputStream} is closed
	 *
	 * @throws IOException
	 */
	public abstract AudioInputStream normalListening() throws LineUnavailableException, IOException;

	/**
	 * This will start listening until a command was spoken or {@code timeout} seconds passed
	 *
	 * @throws LineUnavailableException
	 * @throws IOException
	 */
	@Deprecated
	protected AudioInputStream activeListening(int timeout) throws LineUnavailableException, IOException {
		return new ClosingAudioInputStream(normalListening(),
				Audio.FORMAT,
				AudioSystem.NOT_SPECIFIED,
				volume, false);
	}
	/**
	 * This will start listening until a command was spoken or {@code timeout} seconds passed and return the recorded data.
	 *
	 * @throws LineUnavailableException
	 * @throws IOException
	 */
	// public abstract byte[] activeListeningRaw(int timeout) throws LineUnavailableException, IOException;

	/**
	 * This will wait until a command gets spoken, then return and automatically stop listening once the command is over
	 *
	 * @throws LineUnavailableException
	 * @throws IOException
	 */
	public AudioInputStream listenCommand() throws LineUnavailableException, IOException {
		AudioInputStream stream = formatStream(normalListening());
		ClosingAudioInputStream wait = new ClosingAudioInputStream(new CloseShieldInputStream(stream), stream.getFormat(), AudioSystem.NOT_SPECIFIED, volume, true);
		byte[] buffer = new byte[1024];
		while (wait.read(buffer) != -1)
			;
		wait.close();// Actually not needed
		volume.startSpeaking();
		return new ClosingAudioInputStream(stream, FORMAT, AudioSystem.NOT_SPECIFIED, volume, false);
	}

	public abstract void play(AudioInputStream stream) throws LineUnavailableException, IOException, InterruptedException;

	public void init() throws JackException {
	}

	public void close() throws JackException, IOException {
	}

	public static class DefaultAudio extends Audio {

		public DefaultAudio(JsonObject config) {
			super(config);
		}

		@Override
		public AudioInputStream normalListening() throws LineUnavailableException {
			TargetDataLine line = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, format));
			line.open(format);
			line.start(); // start capturing
			AudioInputStream stream = new AudioInputStream(line);
			stream = formatStream(stream);
			return stream;
		}

		@Override
		public void play(AudioInputStream ais) throws LineUnavailableException, IOException {
			AudioFormat audioFormat = ais.getFormat();
			SourceDataLine line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, audioFormat));
			line.open(audioFormat);
			line.start();

			int count = 0;
			byte[] data = new byte[65532];
			while ((count = ais.read(data)) != -1)
				line.write(data, 0, count);

			line.drain();
			line.close();
		}
	}

	public static class JackAudio extends Audio implements JackProcessCallback, JackSampleRateCallback, JackBufferSizeCallback {

		protected JackClient						client;
		protected JackPort							out, in;
		protected int								sampleRate, bufferSize;
		protected Queue<AudioInputStream>			outQueue	= new LinkedList<>();
		protected Queue<CircularBufferInputStream>	inQueue		= new LinkedList<>();

		public JackAudio(JsonObject config) {
			super(config);
		}

		@Override
		public void init() throws JackException {
			Jack jack = Jack.getInstance();
			client = jack.openClient("VoicePi", EnumSet.noneOf(JackOptions.class), EnumSet.noneOf(JackStatus.class));
			in = client.registerPort("in", JackPortType.AUDIO, EnumSet.of(JackPortFlags.JackPortIsInput));
			out = client.registerPort("out", JackPortType.AUDIO, EnumSet.of(JackPortFlags.JackPortIsOutput));
			client.setSampleRateCallback(this);
			client.setProcessCallback(this);
			client.setBuffersizeCallback(this);
			client.activate();
			sampleRateChanged(client, client.getSampleRate());
			buffersizeChanged(client, client.getBufferSize());
			client.transportStart();
		}

		@Override
		public void close() throws JackException, IOException {
			client.transportStop();
			client.deactivate();
			synchronized (outQueue) {
				for (AudioInputStream in : outQueue)
					in.close();
				outQueue.clear();
			}
			synchronized (inQueue) {
				for (CircularBufferInputStream in : inQueue) {
					in.close();
					in.notifyAll();
				}
				inQueue.clear();
			}
		}

		@Override
		public AudioInputStream normalListening() throws LineUnavailableException, IOException {
			CircularBufferInputStream in = new CircularBufferInputStream(new CircularByteBuffer(bufferSize * 4 * 8));
			synchronized (inQueue) {
				inQueue.add(in);
			}
			AudioInputStream audio = new AudioInputStream(in, format, AudioSystem.NOT_SPECIFIED);
			audio = formatStream(audio);
			return audio;
		}

		@Override
		public void play(AudioInputStream stream) {
			stream = formatStream(stream, format);
			synchronized (outQueue) {
				outQueue.add(stream);
			}
			synchronized (stream) {
				try {
					stream.wait();
				} catch (InterruptedException e) {

				}
				System.out.println("Finished waiting " + stream);
			}
			synchronized (outQueue) {
				outQueue.remove(stream);
			}
		}

		@Override
		public boolean process(JackClient client, int samples) {
			// Process in
			synchronized (inQueue) {
				byte[] inData = new byte[samples * 4];
				in.getBuffer().get(inData);
				inQueue.removeIf(out -> {
					CircularByteBuffer b = out.getBuffer();
					if (b == null)
						return true;
					b.put(inData);
					return false;
				});
			}
			// Process out
			synchronized (outQueue) {
				if (!outQueue.isEmpty()) {
					FloatBuffer outData = out.getFloatBuffer();
					// TODO cache all buffers
					byte[] buffer = new byte[samples * 4];
					ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
					FloatBuffer floatBuffer = byteBuffer.asFloatBuffer();
					boolean first = true;
					for (AudioInputStream stream : outQueue) {
						try {
							int read = stream.read(buffer, 0, samples * 4);
							for (int i = 0; i < read / 4; i++) // TODO if the first stream does not read all samples, this will fail
								outData.put(i, (first ? 0 : outData.get(i)) + floatBuffer.get(i));
							if (read < samples * 4) {
								stream.close();
								synchronized (stream) {
									System.out.println("Notify " + stream);
									stream.notifyAll();
								}
							}
						} catch (IOException e) {
							// TODO exception handling
							e.printStackTrace();
						}
						first = false;
					}
				} else {
					// TODO only write this one time
					out.getFloatBuffer().put(new float[samples]);
				}
			}
			return true;
		}

		@Override
		public void sampleRateChanged(JackClient client, int sampleRate) {
			if (client == JackAudio.this.client) {
				// format = new AudioFormat(sampleRate, 16, 1, true, false);
				format = new AudioFormat(Encoding.PCM_FLOAT, sampleRate, 32, 1, 4, sampleRate, true);
				this.sampleRate = sampleRate;
			}
		}

		@Override
		public void buffersizeChanged(JackClient client, int bufferSize) {
			if (client == JackAudio.this.client) {
				this.bufferSize = bufferSize;
			}
		}

	}

	public static AudioInputStream formatStream(AudioInputStream in) {
		return formatStream(in, FORMAT);
	}

	public static AudioInputStream formatStream(AudioInputStream in, AudioFormat target) {
		if (!in.getFormat().equals(target))
			in = AudioSystem.getAudioInputStream(target, in);
		return in;
	}

	public static byte[] readAllBytes(AudioInputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		IOUtils.copy(in, out);
		return out.toByteArray();
	}
}