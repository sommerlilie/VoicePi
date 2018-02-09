package de.piegames.picontrol.action;

import java.io.IOException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.google.gson.JsonObject;
import de.piegames.picontrol.PiControl;

public abstract class Action {

	protected final Log log = LogFactory.getLog(getClass());

	public static enum ActionType {
		RUN_COMMAND, SAY_TEXT, PLAY_SOUND, NONE;

		public static ActionType forName(String jsonName) {
			String name = jsonName.toUpperCase().replace('-', '_');
			return valueOf(name);
		}
	}

	protected final ActionType	type;
	protected PiControl			control;

	public Action(ActionType type, JsonObject data, PiControl control) {
		this.control = control;
		this.type = type;
	}

	public abstract void execute() throws IOException, InterruptedException;

	public static Action fromJson(JsonObject json, PiControl control) {
		switch (ActionType.forName(json.getAsJsonPrimitive("action").getAsString())) {
			case RUN_COMMAND:
				return new RunCommandAction(json, control);
			case SAY_TEXT:
				return new SayTextAction(json, control);
			case PLAY_SOUND:
				return new PlaySoundAction(json, control);
			case NONE:
			default:
				return DO_NOTHING;
		}
	}

	public static final Action DO_NOTHING = new Action(ActionType.NONE, null, null) {

		@Override
		public void execute() throws IOException, InterruptedException {
		}
	};
}