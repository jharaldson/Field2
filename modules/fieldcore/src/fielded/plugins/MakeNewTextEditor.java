package fielded.plugins;

import field.app.RunLoop;
import field.utility.Dict;
import field.utility.Pair;
import fieldbox.boxes.Box;
import fieldbox.boxes.Boxes;
import fieldbox.boxes.Mouse;
import fieldcef.plugins.TextEditor;
import fielded.Commands;
import fielded.RemoteEditor;
import fielded.ServerSupport;
import fielded.webserver.Server;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * Exports a command for making a new, independent, text editor (with it's own server)
 * Created by marc on 6/8/16.
 */
public class MakeNewTextEditor extends Box {

	static public Dict.Prop<TriFunctionOfBoxAnd<Box, String, Boolean>> setCurrentlyEdited = new Dict.Prop<>("setCurrentlyEdited").type().toCannon();
	static public Dict.Prop<FunctionOfBox<Box>> makeNewTextEditor = new Dict.Prop<>("makeNewTextEditor").type().toCannon();

	public MakeNewTextEditor(Box root) {

		this.properties.put(Boxes.dontSave, true);

		this.properties.put(Commands.commands, () -> {
			if (selection().count() != 1) return Collections.EMPTY_MAP;

			Box target = selection().findFirst().get();

			LinkedHashMap<Pair<String, String>, Runnable> r = new LinkedHashMap<>();
			r.put(new Pair<String, String>("New Text Editor", "Makes a new, independent text editor"), () -> {

				makeNewTextEditor(target);

			});

			return r;
		});

		this.properties.put(makeNewTextEditor, MakeNewTextEditor::makeNewTextEditor);

		this.properties.put(setCurrentlyEdited, (target, to, prop) -> {
			RemoteEditor ed = target.find(RemoteEditor.editor, upwards()).findFirst().get();
			ed.changeSelection(to, new Dict.Prop<String>(prop));
			return true;
		});
	}

	static public Box makeNewTextEditor(Box target) {
		ServerSupport q = new ServerSupport(target);
		Server newServer = target.find(ServerSupport.server, target.upwards()).findFirst().get();

		TextEditor te = new TextEditor(target);
		te.connect(target);
		te.loaded();

		te.pin();
		RunLoop.main.when(q.getRemoteEditor(), e -> e.pin());

		return te.browser;
	}

	private Stream<Box> selection() {
		return breadthFirst(both()).filter(x -> x.properties.isTrue(Mouse.isSelected, false));
	}

}
