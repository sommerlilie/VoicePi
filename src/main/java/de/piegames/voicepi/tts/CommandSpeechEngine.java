package de.piegames.voicepi.tts;

import java.io.IOException;
import java.io.OutputStream;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import com.google.gson.JsonObject;
import de.piegames.voicepi.VoicePi;
import de.piegames.voicepi.action.RunCommand;

public class CommandSpeechEngine extends SpeechEngine {

	protected RunCommand	command;
	protected boolean		noAudio;

	public CommandSpeechEngine(VoicePi control, JsonObject config) {
		super(control, config);
		command = new RunCommand(config.get("command"));
		if (config.has("no-audio"))
			noAudio = config.getAsJsonPrimitive("no-audio").getAsBoolean();
	}

	@Override
	public AudioInputStream generateAudio(String text) {
		try {
			Process process = command.execute();
			OutputStream out = process.getOutputStream();
			out.write(text.getBytes());
			out.close();
			if (noAudio) {
				process.waitFor();
				return null;
			} else
				return AudioSystem.getAudioInputStream(process.getInputStream());
		} catch (IOException | UnsupportedAudioFileException | InterruptedException e) {
			log.warn("Could not speak text: " + text, e);
		}
		return null;
	}
}
