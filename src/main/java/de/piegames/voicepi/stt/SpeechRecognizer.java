package de.piegames.voicepi.stt;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.google.gson.JsonObject;
import de.piegames.voicepi.Settings;
import de.piegames.voicepi.VoicePi;
import de.piegames.voicepi.audio.Audio;
import de.piegames.voicepi.state.VoiceState;

/**
 * <p>
 * A speech recognizer will record audio from an audio source and transcribe what has been said ("speech to text", <i>STT</i>). A {@code SpeechRecohnizer} will
 * take in text from any source (depending on the implementation) and pass it to the application. This may involve performing STT, but other input methods are
 * valid too. This class is the primary interface for the user to communicate with the application.
 * </p>
 * <p>
 * The recognizer, once started, will run in a separate thread where it will be listening for text commands from the user. If it detects any, it will push them
 * onto a queue from where the {@link VoicePi} will take and process it. If the recognizer does get its messages from another source (like a listener or a
 * callback), the {@link #run()} method may be left empty. The implementation of this class can safely assume that {@link #load(BlockingQueue, Set)} will be
 * called before using it and {@link #unload()} after using it and then never use it again. The methods {@link #startRecognition()} and
 * {@link #stopRecognition()} may be called multiple times, but never so that more than one thread might be started.
 * </p>
 * <p>
 * </p>
 */
public abstract class SpeechRecognizer implements Runnable {

	protected final Log							log	= LogFactory.getLog(getClass());
	protected JsonObject						config;
	protected BlockingQueue<Collection<String>>	commandsSpoken;
	protected Thread							thread;
	protected Audio								audio;
	protected Settings							settings;
	protected VoiceState						stateMachine;
	protected volatile boolean					deaf;

	public SpeechRecognizer(JsonObject config) {
		this.config = config;
	}

	/**
	 * This will be called on startup to load all the necessary data to perform STT.
	 * @param commandsSpoken a {@link BlockingQueue} where to put all commands that got recognized. {@link VoicePi} will take them from the queue and process
	 *            them. The queue is not size limited and will never block while adding items to it.
	 * @param commands a set of commands that got registered by the modules. If the STT is using a finite vocabulary, this is it. If the STT works with
	 *            arbitrary sentences, it may be used to improve recognition quality.
	 *
	 * @throws IOException if loading fails
	 * @throws NullPointerException if audio is {@code null} but shouldn't be (this depends on the implementation)
	 */
	public void load(Audio audio, VoiceState stateMachine, Settings settings, BlockingQueue<Collection<String>> commandsSpoken, Set<String> commands) throws IOException {
		this.commandsSpoken = Objects.requireNonNull(commandsSpoken);
		this.audio = audio;
		this.settings = settings;
		this.stateMachine = stateMachine;
	}

	/**
	 * Called in a background thread. This method will continuously listen for any spoken commands and add them to {@code #commandsSpoken}. It is expected to
	 * return if {@link Thread#interrupt()} is called.
	 */
	@Override
	public abstract void run();

	// public abstract Collection<String> nextCommand() throws Exception;

	/** Starts the listening process in a background thread. The process might be started and stopped multiple times. */
	public void startRecognition() {
		log.debug("Starting " + getClass().getSimpleName());
		thread = new Thread(this);
		thread.setName(getClass().getSimpleName() + " listening thread");
		thread.start();
	}

	/**
	 * Stops the listening process. If {@code #run()} does not quit when interrupted, this method should be overwritten to stop the thread in less friendly
	 * ways.
	 */
	public void stopRecognition() {
		log.debug("Stopping " + getClass().getSimpleName());
		thread.interrupt();
		try {
			thread.join(10000);
		} catch (InterruptedException e) {
			log.warn("Could not make sure that the recognizer thread has finished", e);
		}
		thread = null;
	}

	protected boolean isRunning() {
		return thread != null;
	}

	protected void commandSpoken(String command) {
		commandSpoken(Arrays.asList(command));
	}

	protected void commandSpoken(Collection<String> command) {
		log.debug("Command spoken [" + String.join(", ", command) + "]");
		commandsSpoken.offer(command);
	}

	/**
	 * This will stop the recognizer from listening. The recognizer will not "hear" anything until {@code #undeafenRecognition()} is called. This is to prevent
	 * recording the output of the speech synthesis as command again. This should have no effect to those recognizers who don't rely on the microphone.
	 * <p/>
	 * When this method is called it cannot be guaranteed that the recognizer will be running!
	 */
	public void deafenRecognition(boolean deaf) {
		if (!isRunning())
			return;
		log.debug((deaf ? "Pausing " : "Resuming ") + getClass().getSimpleName());
		this.deaf = deaf;
	}

	public abstract boolean transcriptionSupported();

	public List<String> transcribe() {
		throw new UnsupportedOperationException();
	}

	/** Unloads and releases all resources. The object won't be used after this method has been called. */
	public void unload() {
		log.debug("Unloading " + getClass().getSimpleName());
		commandsSpoken = null;
	}
}