package fielded.scratch;

import field.message.MessageQueue;
import field.utility.Dict;
import field.utility.Pair;
import field.utility.Quad;
import field.utility.Triple;
import field.graphics.RunLoop;
import fieldbox.boxes.Box;
import fieldbox.boxes.Boxes;
import fieldbox.boxes.Watches;
import fielded.Execution;
import fielded.RemoteEditor;
import fielded.webserver.Server;
import fieldnashorn.Nashorn;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Created by marc on 3/26/14.
 */
public class ServerSupport {

	static List<String> playlist = Arrays.asList("messagebus.js", "instantiate.js", "changehooks.js", "status.js", "modal.js", "brackets.js", "output.js", "doubleshift.js");

	public ServerSupport(Boxes boxes)
	{

		Watches watches = boxes.root().first(Watches.watches).orElseThrow(() -> new IllegalArgumentException(" need Watches for server support"));
		MessageQueue<Quad<Dict.Prop, Box, Object, Object>, String> queue = watches.getQueue();


		try {

			// todo: these need to be random, unallocated ports

			Server s = new Server(8080, 8081);
			s.setFixedResource("/init", readFile("/home/marc/fieldwork2/fielded/internal/init.html"));
			s.addDocumentRoot("/home/marc/fieldwork2/fielded/internal/");
			s.addDocumentRoot("/home/marc/fieldwork2/fielded/external/");

			s.addHandlerLast(x -> x.equals("alive"), (server, socket, address, payload) -> {
				System.out.println(" alive :" + payload);
				return payload;
			});

			s.addHandlerLast(x -> x.equals("log"), (server, socket, address, payload) -> {
				System.out.println("-\n"+payload+"\n-");
				return payload;
			});
			s.addHandlerLast(x -> x.equals("error"), (server, socket, address, payload) -> {
				System.out.println("-e-\n"+payload+"\n-e-");
				return payload;
			});

			s.addHandlerLast(x -> x.equals("initialize"), (server, socket, address, payload) -> {
				s.send(socket, readFile("/home/marc/fieldwork2/fielded/internal/include.js"));
				return payload;
			});



			s.addHandlerLast(x -> x.equals("initialize.finished"), (server, socket, address, payload) -> {

				for (String n : playlist) {
					s.send(socket, readFile("/home/marc/fieldwork2/fielded/internal/" + n));
				}

				System.out.println(" payload is :"+payload);

				String name = payload + "";

				System.out.println(" naming socket "+name+" = "+socket);

				s.nameSocket(name, socket);

				System.out.println(" initializing remote editor ");

				RemoteEditor ed = new RemoteEditor(s, name, watches, queue);
				ed.connect(boxes.root());
				ed.setCurrentlyEditingProperty(Execution.code);

				return payload;
			});


		} catch (IOException e) {
		}
	}

	private static String readFile(String s) {
		try (BufferedReader r = new BufferedReader(new FileReader(new File(s)))) {
			String line = "";
			while (r.ready()) {
				line += r.readLine() + "\n";
			}
			return line;

		} catch (IOException e) {
			e.printStackTrace();
		}
		return "";
	}

	static public void openEditor() throws IOException {
		//TODO: alternative OS's
		new ProcessBuilder("/usr/bin/google-chrome", "--app=http://localhost:8080/init").redirectOutput(ProcessBuilder.Redirect.to(File.createTempFile("field", "browseroutput"))).redirectError(File.createTempFile("field", "browsererror")).start();

	}

}
