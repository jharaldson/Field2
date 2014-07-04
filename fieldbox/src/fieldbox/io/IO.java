package fieldbox.io;

import field.utility.Dict;
import fieldbox.boxes.Box;
import fieldbox.boxes.Boxes;
import fieldbox.boxes.FrameManipulation;
import fielded.Execution;

import java.io.*;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.stream.Collectors;

/**
 * This class handles the persistence of Box graphs to disk. Key design challenge here is to allow individual properties of boxes to exist as separate
 * files on disk if it makes sense to do so (for example disparate pieces of source code), and otherwise have properties associated with a box bound
 * up in a single file the rides near these source code files. These boxes are free to float around the filesystem and to be shared between documents.
 * This is in stark contrast to the Field1 design where a Field "sheet" was a directory that contained all of its boxes. Sharing boxes between
 * documents was therefore completely impossible. This structure will give us the ability to drag and drop documents into the Field window and save
 * side-car .fieldbox files next to them that contain the properties.
 *
 * .fieldbox (the properties) and .field2 (the master document) files are stored as EDN files (see EDN.java for specifics).
 */
public class IO {
	static public final String WORKSPACE = "{{workspace}}";
	static public final String EXECUTION = "{{execution}}";

	/**
	 * tag interface for boxes, method will be called after all boxes have been loaded (and all properties set)
	 */
	static public interface Loaded {
		public void loaded();
	}

	private final String defaultDirectory;
	static Set<String> knownProperties = new LinkedHashSet<String>();
	static Map<String, Filespec> knownFiles = new HashMap<String, Filespec>();

	public static final Dict.Prop<String> id = new Dict.Prop<>("__id__");

	public void addFilespec(String name, String defaultSuffix, String language) {
		Filespec f = new Filespec();
		f.name = name;
		f.defaultSuffix = defaultSuffix;
		f.language = language;
		knownFiles.put(f.name, f);
	}

	public class Filespec {
		public String name;
		private String defaultSuffix;
		private String language;

		public String getDefaultSuffix(Box box) {

			if (defaultSuffix.equals(EXECUTION)) {
				Execution e = box.first(Execution.execution)
					    .orElseThrow(() -> new IllegalArgumentException(" no execution for box " + box));
				return e.support(e, new Dict.Prop<String>(name)).getDefaultFileExtension();
			}

			return defaultSuffix;
		}

		public String getLanguage(Box box) {

			if (defaultSuffix.equals(EXECUTION)) {
				Execution e = box.first(Execution.execution)
					    .orElseThrow(() -> new IllegalArgumentException(" no execution for box " + box));
				return e.support(e, new Dict.Prop<String>(name)).getDefaultFileExtension();
			}

			return language;
		}
	}

	public IO(String defaultDirectory) {
		this.defaultDirectory = defaultDirectory;

		if (!new File(defaultDirectory).exists()) new File(defaultDirectory).mkdir();

		knownProperties.add(Box.name.getName());
		knownProperties.add(Box.frame.getName());

		knownProperties.add(FrameManipulation.lockHeight.getName());
		knownProperties.add(FrameManipulation.lockWidth.getName());
	}

	public Document compileDocument(Box documentRoot, Map<Box, String> specialBoxes) {
		Document d = new Document();
		d.externalList = documentRoot.breadthFirst(documentRoot.downwards()).map(box -> toExternal(box, specialBoxes)).filter(x -> x != null)
			    .collect(Collectors.toList());
		d.knownFiles = new LinkedHashMap<>(knownFiles);
		d.knownProperties = new LinkedHashSet<>(knownProperties);
		return d;
	}

	public String getDefaultDirectory() {
		return defaultDirectory;
	}

	boolean lastWasNew = false;

	public Document readDocument(String filename, Map<String, Box> specialBoxes, Set<Box> created) {
		File f = filenameFor(filename);

		lastWasNew = false;

		if (!f.exists()) {
			// new file
			lastWasNew = true;

			Document d = new Document();
			d.externalList = new ArrayList<>();
			d.knownFiles = new LinkedHashMap<>();
			d.knownProperties = new LinkedHashSet<>();
			return d;
		}

		String m = readFromFile(f);

		Document d = (Document) new EDN().read(m);
		Map<String, Box> loaded = new HashMap<String, Box>();
		for (External e : d.externalList) {
			fromExternal(e, specialBoxes);
			if (e.box != null) {
				loaded.put(e.id, e.box);
				e.box.properties.put(id, e.id);
			}
		}

		for (External e : d.externalList) {
			for (String id : e.children) {
				Box mc = specialBoxes.getOrDefault(id, loaded.get(id));

				System.out.println(" connecting :" + e.box + " -> " + mc);

				if (mc != null) e.box.connect(mc);
				else System.err.println(" lost child ? " + id + " of " + e.box + " " + specialBoxes);
			}
			for (String id : e.parents) {
				Box mc = specialBoxes.getOrDefault(id, loaded.get(id));

				System.out.println(" connecting :" + e.box + " <- " + mc);

				if (mc != null) mc.connect(e.box);
				else System.err.println(" lost child ? " + id + " of " + e.box + " " + specialBoxes);
			}
		}
		created.addAll(loaded.values());

		loaded.values().stream().filter(b -> b instanceof Loaded).forEach(b -> {
			try {
				((Loaded) b).loaded();
			} catch (Throwable t) {
				System.out.println(" exception thrown while finishing loading for box " + b);
				t.printStackTrace();
				System.out.println(" continuing on...");
			}
		});

		return d;


	}

	private void fromExternal(External ex, Map<String, Box> specialBoxes) {

//		ex.box = new Box();
		try {
			Class c = this.getClass().getClassLoader().loadClass(ex.boxClass);
			Constructor<Box> cc = c.getDeclaredConstructor();
			cc.setAccessible(true);
			ex.box = (Box) cc.newInstance();
		} catch (Throwable e) {
			System.out.println(" while looking for class <" + ex.boxClass + "> needed for <" + ex.id + " / " + ex.textFiles + "> an exception was thrown");
			e.printStackTrace();
			System.out.println(" will proceed with just a vanilla Box class, but custom behavior will be lost ");
			ex.box = new Box();
		}

		for (Map.Entry<String, String> e : ex.textFiles.entrySet()) {
			File filename = filenameFor(e.getValue());
			String text = readFromFile(filename);
			ex.box.properties.put(new Dict.Prop<String>(e.getKey()), text);
		}

		File dataFile = filenameFor(ex.dataFile);

		Map<?, ?> m = (Map) (serializeFromString(readFromFile(dataFile)));
		for (Map.Entry<?, ?> entry : m.entrySet()) {
			ex.box.properties.put(new Dict.Prop((String) entry.getKey()), entry.getValue());
		}

	}


	protected External toExternal(Box box, Map<Box, String> specialBoxes) {
		if (specialBoxes.containsKey(box)) return null;

		if (box.properties.isTrue(Boxes.dontSave, false)) return null;

		External ex = new External();

		for (Map.Entry<Dict.Prop, Object> e : new LinkedHashMap<>(box.properties.getMap()).entrySet()) {
			if (knownFiles.containsKey(e.getKey().getName())) {
				Filespec f = knownFiles.get(e.getKey().getName());

				String extantFilename = box.properties.get(new Dict.Prop<String>("__filename__" + e.getKey().getName()));
				if (extantFilename == null) {
					box.properties.put(new Dict.Prop<String>("__filename__" + e.getKey()
						    .getName()), extantFilename = makeFilenameFor(f, box));
				}
				knownProperties.add("__filename__" + e.getKey().getName());
				ex.textFiles.put(e.getKey().getName(), extantFilename);
			}
		}

		ex.dataFile = box.properties.computeIfAbsent(new Dict.Prop<String>("__datafilename__"), (k) -> makeDataFilenameFor(ex, box));
		knownProperties.add("__datafilename__");
		ex.box = box;
		ex.id = box.properties.computeIfAbsent(id, (k) -> UUID.randomUUID().toString());

		ex.parents = new LinkedHashSet<>();
		ex.children = new LinkedHashSet<>();

		Set<Box> p = box.parents();
		for (Box pp : p) {
			if (specialBoxes.containsKey(pp)) ex.parents.add(specialBoxes.get(pp));
			else ex.parents.add(pp.properties.computeIfAbsent(id, (k) -> UUID.randomUUID().toString()));
		}

		p = box.children();
		for (Box pp : p) {
			if (specialBoxes.containsKey(pp)) ex.children.add(specialBoxes.get(pp));
			else ex.children.add(pp.properties.computeIfAbsent(id, (k) -> UUID.randomUUID().toString()));
		}

		ex.boxClass = box.getClass().getName();

		return ex;
	}

	public void writeOutDocument(String filename, Document d) throws IOException {
		for (External e : d.externalList)
			writeOutExternal(e);
		writeToFile(filenameFor(filename), serializeToString(d));
	}


	protected void writeOutExternal(External external) throws IOException {
		for (Map.Entry<String, String> e : external.textFiles.entrySet()) {
			String text = external.box.properties.get(new Dict.Prop<String>(e.getKey()));
			if (text == null) continue;
			File filename = filenameFor(e.getValue());
			writeToFile(filename, text);
		}
		;

		File dataFile = filenameFor(external.dataFile);
		if (dataFile != null) {
			Map<String, Object> data = new LinkedHashMap<String, Object>();
			for (String kp : knownProperties) {
				Object d = external.box.properties.get(new Dict.Prop(kp));
				if (d != null) data.put(kp, d);
			}
			writeToFile(dataFile, serializeToString(data));
		}
	}

	private String serializeToString(Object data) {
		String written = edn.write(data);
		System.out.println("edn is " + written);
		return written;
	}

	private Object serializeFromString(String data) {
		Object written = edn.read(data);
		return written;
	}

	EDN edn = new EDN();

	private void writeToFile(File filename, String text) throws IOException {
		System.out.println(" would write :" + text + " to " + filename);
		BufferedWriter w = new BufferedWriter(new FileWriter(filename));
		w.append(text);
		w.close();
	}

	static public String readFromFile(File f) {
		try (BufferedReader r = new BufferedReader(new FileReader(f))) {
			String m = "";
			while (r.ready()) m += r.readLine() + "\n";
			return m;
		} catch (IOException e) {
			e.printStackTrace();
		}
		return "";
	}


	private File filenameFor(String value) {
		if (value.startsWith(WORKSPACE)) {
			return new File(defaultDirectory, value.substring(WORKSPACE.length()));
		}
		return new File(value);
	}

	private String makeDataFilenameFor(External ex, Box box) {
		if (ex.textFiles.size() > 0) {
			return ex.textFiles.values().iterator().next() + ".box";
		}
		return makeFilenameFor(".box", "", box);
	}

	private String makeFilenameFor(Filespec f, Box box) {
		return makeFilenameFor(f.getDefaultSuffix(box), f.name, box);
	}

	private String makeFilenameFor(String defaultSuffix, String defaultName, Box box) {
		String name = box.properties.get(Box.name);
		if (name == null) name = "untitled_box";

		String suffix = "_" + defaultName + (defaultSuffix == null ? "" : defaultSuffix);
		name = name + suffix;

		int n = 0;
		if (new File(defaultDirectory, name).exists()) {
			String n2 = name;
			while (new File(defaultDirectory, n2).exists()) {
				n2 = name.substring(0, name.length() - suffix.length()) + pad(n) + suffix;
				n++;
			}
			name = n2;
		}

		return WORKSPACE + name;
	}


	static private String pad(int n) {
		String r = "" + n;
		while (r.length() < 5) r = "0" + r;
		return r;
	}

	static public class Document {
		public List<External> externalList;
		public Map<String, Filespec> knownFiles;
		public Set<String> knownProperties;
	}

	static public class External {
		public Map<String, String> textFiles = new LinkedHashMap<String, String>();
		public String dataFile;
		public String id;
		public String boxClass;

		transient Box box;

		public Set<String> parents;
		public Set<String> children;
	}


	static public void persist(Dict.Prop prop) {
		knownProperties.add(prop.getName());
	}

}
