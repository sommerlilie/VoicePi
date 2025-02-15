package de.piegames.voicepi.stt;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.piegames.voicepi.Settings;
import de.piegames.voicepi.VoicePi;
import de.piegames.voicepi.audio.Audio;
import de.piegames.voicepi.state.VoiceState;
import javafx.util.Pair;

public class MultiRecognizer extends SpeechRecognizer {

	protected List<Pair<SpeechRecognizer, List<String>>>	recognizers;
	protected boolean										onlyOne;

	public MultiRecognizer(JsonObject config) {
		super(config);
		recognizers = new ArrayList<>();

		onlyOne = config.getAsJsonPrimitive("only-one-active").getAsBoolean();
		for (JsonElement e : config.getAsJsonArray("engines")) {
			JsonObject stt = e.getAsJsonObject();
			try {
				SpeechRecognizer sr = (SpeechRecognizer) Class.forName(stt.getAsJsonPrimitive("class-name").getAsString())
						.getConstructor(VoicePi.class, JsonObject.class)
						.newInstance(stt);

				List<String> activate = null;
				if (stt.isJsonNull() || !stt.has("active-on")) {

				} else if (stt.get("active-on").isJsonPrimitive()) {
					activate = Collections.singletonList(stt.getAsJsonPrimitive("active-on").getAsString());
				} else {
					List<String> ls = new ArrayList<>();
					for (JsonElement f : stt.getAsJsonArray("active-on"))
						ls.add(f.getAsString());
					activate = ls;
				}
				if (activate == null)
					activate = Collections.singletonList("*:*");
				recognizers.add(new Pair<>(sr, activate));
			} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException | ClassNotFoundException e1) {
				log.warn("Could not instantiate speech recognizer " + stt.getAsJsonPrimitive("class-name").getAsString(), e1);
			}
		}
	}

	public MultiRecognizer(List<Pair<SpeechRecognizer, List<String>>> recognizers, boolean onlyOne) {
		super(null);
		this.recognizers = new ArrayList<>(Objects.requireNonNull(recognizers));
		this.onlyOne = onlyOne;
	}

	@Override
	public void load(Audio audio, VoiceState stateMachine, Settings settings, BlockingQueue<Collection<String>> commandsSpoken, Set<String> commands) throws IOException {
		super.load(audio, stateMachine, settings, commandsSpoken, commands);
		for (Pair<SpeechRecognizer, List<String>> r : recognizers)
			r.getKey().load(audio, null, null, commandsSpoken, commands);
		stateMachine.current.addListener((obj, oldVal, newVal) -> {
			if (isRunning())
				startRecognition();
		});
	}

	@Override
	public void run() {
	}

	@Override
	public void startRecognition() {
		super.startRecognition();
		boolean alreadyOne = false;
		for (Pair<SpeechRecognizer, List<String>> r : recognizers)
			if (r.getValue().stream().anyMatch(stateMachine.getCurrentState()::matches) && !(onlyOne && alreadyOne)) {
				if (!r.getKey().isRunning())
					r.getKey().startRecognition();
				alreadyOne = true;
			} else {
				if (r.getKey().isRunning())
					r.getKey().stopRecognition();
			}
	}

	@Override
	public void stopRecognition() {
		for (Pair<SpeechRecognizer, List<String>> r : recognizers)
			if (r.getKey().isRunning())
				r.getKey().stopRecognition();
	}

	@Override
	public void deafenRecognition(boolean deaf) {
		for (Pair<SpeechRecognizer, List<String>> r : recognizers)
			r.getKey().deafenRecognition(deaf);
	}

	@Override
	public boolean transcriptionSupported() {
		return false;// TODO
	}

	@Override
	public void unload() {
		for (Pair<SpeechRecognizer, List<String>> r : recognizers)
			r.getKey().unload();
		recognizers.clear();
	}
}
