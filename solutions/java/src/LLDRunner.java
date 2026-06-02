import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LLDRunner {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z_][\\w.]*)\\s*;");
    private static final Pattern MAIN_PATTERN = Pattern.compile(
            "public\\s+static\\s+void\\s+main\\s*\\(\\s*String\\s*(?:\\[\\s*\\]|\\.\\.\\.)\\s*\\w+\\s*\\)");
    private static final Pattern RUN_PATTERN = Pattern.compile("public\\s+static\\s+void\\s+run\\s*\\(\\s*\\)");

    public static void main(String[] args) {
        try {
            Path projectRoot = locateProjectRoot();
            Path sourceRoot = projectRoot.resolve("src");
            List<DemoInfo> demos = discoverDemos(sourceRoot);

            if (args.length > 0 && "--list".equals(args[0])) {
                printDemos(demos);
                return;
            }

            if (args.length > 0 && "--gui".equals(args[0])) {
                launchGui(projectRoot, sourceRoot, demos);
                return;
            }

            String requestedDemo = null;
            String[] demoArgs = new String[0];
            if (args.length > 0) {
                if ("--demo".equals(args[0])) {
                    if (args.length < 2) {
                        throw new IllegalArgumentException("Missing demo id after --demo.");
                    }
                    requestedDemo = args[1];
                    demoArgs = Arrays.copyOfRange(args, 2, args.length);
                } else {
                    requestedDemo = args[0];
                    demoArgs = Arrays.copyOfRange(args, 1, args.length);
                }
            }

            DemoInfo demo = requestedDemo == null
                    ? promptForDemo(demos)
                    : findDemo(demos, requestedDemo);
            if (demo == null) {
                return;
            }

            runDemo(projectRoot, sourceRoot, demo, demoArgs);
        } catch (Exception ex) {
            System.err.println("Unable to run demo: " + ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static Path locateProjectRoot() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        List<Path> candidates = new ArrayList<>();
        candidates.add(cwd);
        candidates.add(cwd.resolve("solutions").resolve("java"));

        Path cursor = cwd;
        while (cursor != null) {
            candidates.add(cursor);
            candidates.add(cursor.resolve("solutions").resolve("java"));
            cursor = cursor.getParent();
        }

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate.resolve("src").resolve("LLDRunner.java"))) {
                return candidate.normalize();
            }
        }

        throw new IllegalStateException("Could not find solutions/java/src/LLDRunner.java from " + cwd);
    }

    private static List<DemoInfo> discoverDemos(Path sourceRoot) throws IOException {
        List<DemoInfo> demos = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(sourceRoot)) {
            stream.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !"LLDRunner.java".equals(path.getFileName().toString()))
                    .forEach(path -> addDemoIfRunnable(sourceRoot, demos, path));
        }

        removeRedundantMainClasses(demos);
        Collections.sort(demos, Comparator.comparing(DemoInfo::getSortKey));
        assignStableIds(demos);
        return demos;
    }

    private static void addDemoIfRunnable(Path sourceRoot, List<DemoInfo> demos, Path sourceFile) {
        String fileName = sourceFile.getFileName().toString();
        if (!fileName.endsWith("Demo.java") && !"Main.java".equals(fileName)) {
            return;
        }

        try {
            String source = new String(Files.readAllBytes(sourceFile), StandardCharsets.UTF_8);
            boolean hasMain = MAIN_PATTERN.matcher(source).find();
            boolean hasRun = RUN_PATTERN.matcher(source).find();
            if (!hasMain && !hasRun) {
                return;
            }

            Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
            String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
            String simpleClassName = fileName.substring(0, fileName.length() - ".java".length());
            String className = packageName.isEmpty() ? simpleClassName : packageName + "." + simpleClassName;
            Path relativePath = sourceRoot.relativize(sourceFile);
            String rootFolder = relativePath.getNameCount() == 1 ? "" : relativePath.getName(0).toString();

            demos.add(new DemoInfo(rootFolder, simpleClassName, className, sourceFile, hasMain, hasRun));
        } catch (IOException ex) {
            throw new RuntimeException("Failed to read " + sourceFile, ex);
        }
    }

    private static void removeRedundantMainClasses(List<DemoInfo> demos) {
        Map<String, Boolean> rootHasDemo = new HashMap<>();
        for (DemoInfo demo : demos) {
            if (demo.simpleClassName.endsWith("Demo")) {
                rootHasDemo.put(demo.rootFolder, true);
            }
        }

        demos.removeIf(demo -> "Main".equals(demo.simpleClassName)
                && Boolean.TRUE.equals(rootHasDemo.get(demo.rootFolder)));
    }

    private static void assignStableIds(List<DemoInfo> demos) {
        Map<String, List<DemoInfo>> byRoot = new LinkedHashMap<>();
        for (DemoInfo demo : demos) {
            String root = demo.rootFolder.isEmpty()
                    ? demo.simpleClassName.toLowerCase(Locale.ROOT)
                    : demo.rootFolder.toLowerCase(Locale.ROOT);
            byRoot.computeIfAbsent(root, ignored -> new ArrayList<>()).add(demo);
        }

        Set<String> used = new HashSet<>();
        for (Map.Entry<String, List<DemoInfo>> entry : byRoot.entrySet()) {
            List<DemoInfo> group = entry.getValue();
            if (group.size() == 1) {
                group.get(0).id = entry.getKey();
                used.add(entry.getKey());
                continue;
            }

            DemoInfo preferred = null;
            for (DemoInfo demo : group) {
                if (!"Main".equals(demo.simpleClassName)) {
                    preferred = demo;
                    break;
                }
            }

            if (preferred != null) {
                preferred.id = entry.getKey();
                used.add(entry.getKey());
            }

            for (DemoInfo demo : group) {
                if (demo.id != null) {
                    continue;
                }

                String candidate = toKebabCase(demo.simpleClassName);
                while (used.contains(candidate)) {
                    candidate = entry.getKey() + "-" + candidate;
                }
                demo.id = candidate;
                used.add(candidate);
            }
        }
    }

    private static DemoInfo promptForDemo(List<DemoInfo> demos) {
        printDemos(demos);
        System.out.print("\nEnter demo number or id: ");

        Scanner scanner = new Scanner(System.in);
        if (!scanner.hasNextLine()) {
            System.out.println("\nNo demo selected.");
            return null;
        }

        String selection = scanner.nextLine().trim();
        if (selection.isEmpty()) {
            System.out.println("No demo selected.");
            return null;
        }

        if (selection.chars().allMatch(Character::isDigit)) {
            int index = Integer.parseInt(selection);
            if (index < 1 || index > demos.size()) {
                throw new IllegalArgumentException("Demo number must be between 1 and " + demos.size() + ".");
            }
            return demos.get(index - 1);
        }

        return findDemo(demos, selection);
    }

    private static DemoInfo findDemo(List<DemoInfo> demos, String query) {
        String normalizedQuery = normalize(query);
        List<DemoInfo> matches = new ArrayList<>();

        for (DemoInfo demo : demos) {
            if (demo.matches(normalizedQuery)) {
                matches.add(demo);
            }
        }

        if (matches.isEmpty()) {
            throw new IllegalArgumentException("Unknown demo '" + query + "'. Run with --list to see available demos.");
        }

        if (matches.size() > 1) {
            System.err.println("More than one demo matched '" + query + "':");
            for (DemoInfo demo : matches) {
                System.err.println("  " + demo.id + " (" + demo.className + ")");
            }
            throw new IllegalArgumentException("Use a more specific id or class name.");
        }

        return matches.get(0);
    }

    private static void printDemos(List<DemoInfo> demos) {
        System.out.println("Available LLD demos:");
        for (int i = 0; i < demos.size(); i++) {
            DemoInfo demo = demos.get(i);
            System.out.printf("%2d. %-32s %s%n", i + 1, demo.id, demo.className);
        }
        System.out.println("\nExamples:");
        System.out.println("  java src/LLDRunner.java parkinglot");
        System.out.println("  java src/LLDRunner.java --demo vendingmachine");
        System.out.println("  java src/LLDRunner.java --gui");
    }

    private static void runDemo(Path projectRoot, Path sourceRoot, DemoInfo demo, String[] demoArgs) throws Exception {
        System.out.println("\n==> Running " + demo.className);
        Path buildDir = projectRoot.resolve("target").resolve("lld-runner").resolve(demo.id);
        recreateDirectory(buildDir);
        compileDemo(sourceRoot, buildDir, demo);
        invokeDemo(buildDir, demo, demoArgs);
    }

    private static void compileDemo(Path sourceRoot, Path buildDir, DemoInfo demo) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A JDK is required because demos are compiled on demand. A JRE is not enough.");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(
                    Collections.singletonList(demo.sourceFile.toFile()));

            List<String> options = Arrays.asList(
                    "-encoding", "UTF-8",
                    "-d", buildDir.toString(),
                    "-sourcepath", sourceRoot.toString(),
                    "-classpath", buildClassPath(buildDir)
            );

            Boolean success = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
            if (!Boolean.TRUE.equals(success)) {
                printDiagnostics(diagnostics);
                throw new IllegalStateException("Compilation failed for " + demo.className + ".");
            }
        }
    }

    private static String buildClassPath(Path buildDir) {
        String currentClassPath = System.getProperty("java.class.path", "");
        if (currentClassPath.trim().isEmpty()) {
            return buildDir.toString();
        }
        return buildDir + System.getProperty("path.separator") + currentClassPath;
    }

    private static void printDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            JavaFileObject source = diagnostic.getSource();
            String sourceName = source == null ? "unknown source" : Paths.get(source.toUri()).toString();
            System.err.printf("%s:%d: %s: %s%n",
                    sourceName,
                    diagnostic.getLineNumber(),
                    diagnostic.getKind().name().toLowerCase(Locale.ROOT),
                    diagnostic.getMessage(Locale.ROOT));
        }
    }

    private static void invokeDemo(Path buildDir, DemoInfo demo, String[] demoArgs) throws Exception {
        URL[] urls = {buildDir.toUri().toURL()};
        try (URLClassLoader classLoader = new URLClassLoader(urls, LLDRunner.class.getClassLoader())) {
            ClassLoader previousClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(classLoader);
            try {
                Class<?> demoClass = Class.forName(demo.className, true, classLoader);
                if (demo.hasMain) {
                    Method main = demoClass.getMethod("main", String[].class);
                    main.invoke(null, (Object) demoArgs);
                } else {
                    Method run = demoClass.getMethod("run");
                    run.invoke(null);
                }
            } catch (InvocationTargetException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                throw ex;
            } finally {
                Thread.currentThread().setContextClassLoader(previousClassLoader);
            }
        }
    }

    private static void launchGui(Path projectRoot, Path sourceRoot, List<DemoInfo> demos) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("LLD Demo Launcher");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setMinimumSize(new Dimension(900, 560));

            JTextField searchField = new JTextField();
            DefaultListModel<DemoInfo> model = new DefaultListModel<>();
            JList<DemoInfo> demoList = new JList<>(model);
            demoList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            JTextArea output = new JTextArea();
            output.setEditable(false);

            Runnable refreshList = () -> {
                String query = normalize(searchField.getText());
                model.clear();
                for (DemoInfo demo : demos) {
                    if (query.isEmpty() || demo.matchesFuzzy(query)) {
                        model.addElement(demo);
                    }
                }
                if (!model.isEmpty()) {
                    demoList.setSelectedIndex(0);
                }
            };

            searchField.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    refreshList.run();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    refreshList.run();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    refreshList.run();
                }
            });

            JLabel status = new JLabel("Choose a demo and click Run.");
            JButton runButton = new JButton("Run Selected Demo");
            runButton.addActionListener(event -> runSelectedDemo(projectRoot, sourceRoot, demoList, output, status, runButton));
            demoList.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent event) {
                    if (event.getClickCount() == 2) {
                        runSelectedDemo(projectRoot, sourceRoot, demoList, output, status, runButton);
                    }
                }
            });

            JPanel top = new JPanel(new BorderLayout(8, 8));
            top.add(new JLabel("Search:"), BorderLayout.WEST);
            top.add(searchField, BorderLayout.CENTER);

            JPanel bottom = new JPanel(new BorderLayout(8, 8));
            bottom.add(status, BorderLayout.CENTER);
            bottom.add(runButton, BorderLayout.EAST);

            JSplitPane splitPane = new JSplitPane(
                    JSplitPane.HORIZONTAL_SPLIT,
                    new JScrollPane(demoList),
                    new JScrollPane(output));
            splitPane.setDividerLocation(330);

            frame.add(top, BorderLayout.NORTH);
            frame.add(splitPane, BorderLayout.CENTER);
            frame.add(bottom, BorderLayout.SOUTH);

            refreshList.run();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    private static void runSelectedDemo(Path projectRoot, Path sourceRoot, JList<DemoInfo> demoList, JTextArea output,
                                        JLabel status, JButton runButton) {
        DemoInfo selected = demoList.getSelectedValue();
        if (selected == null) {
            return;
        }

        runButton.setEnabled(false);
        output.setText("");
        status.setText("Running " + selected.id + "...");

        Thread worker = new Thread(() -> {
            PrintStream previousOut = System.out;
            PrintStream previousErr = System.err;
            PrintStream guiStream = new PrintStream(new TextAreaOutputStream(output), true);
            System.setOut(guiStream);
            System.setErr(guiStream);

            try {
                runDemo(projectRoot, sourceRoot, selected, new String[0]);
                appendOutput(output, "\nDone.\n");
                SwingUtilities.invokeLater(() -> status.setText("Finished " + selected.id + "."));
            } catch (Throwable ex) {
                ex.printStackTrace(System.err);
                SwingUtilities.invokeLater(() -> status.setText("Failed " + selected.id + "."));
            } finally {
                System.setOut(previousOut);
                System.setErr(previousErr);
                SwingUtilities.invokeLater(() -> runButton.setEnabled(true));
            }
        }, "lld-demo-runner");
        worker.start();
    }

    private static void appendOutput(JTextArea output, String text) {
        SwingUtilities.invokeLater(() -> {
            output.append(text);
            output.setCaretPosition(output.getDocument().getLength());
        });
    }

    private static void recreateDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        Files.createDirectories(directory);
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String toKebabCase(String value) {
        return value.replaceAll("([a-z])([A-Z])", "$1-$2")
                .replaceAll("([A-Z])([A-Z][a-z])", "$1-$2")
                .toLowerCase(Locale.ROOT);
    }

    private static String displayName(String simpleClassName) {
        String name = simpleClassName.replaceAll("(Demo|Main)$", "");
        return name.replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("([A-Z])([A-Z][a-z])", "$1 $2")
                .trim();
    }

    private static final class DemoInfo {
        private final String rootFolder;
        private final String simpleClassName;
        private final String className;
        private final Path sourceFile;
        private final boolean hasMain;
        private final boolean hasRun;
        private String id;
        private final Map<String, String> searchableValues = new HashMap<>();

        private DemoInfo(String rootFolder, String simpleClassName, String className, Path sourceFile,
                         boolean hasMain, boolean hasRun) {
            this.rootFolder = rootFolder;
            this.simpleClassName = simpleClassName;
            this.className = className;
            this.sourceFile = sourceFile;
            this.hasMain = hasMain;
            this.hasRun = hasRun;
        }

        private String getSortKey() {
            return rootFolder + "." + simpleClassName;
        }

        private boolean matches(String normalizedQuery) {
            ensureSearchableValues();
            for (String value : searchableValues.values()) {
                if (value.equals(normalizedQuery)) {
                    return true;
                }
            }
            return false;
        }

        private boolean matchesFuzzy(String normalizedQuery) {
            ensureSearchableValues();
            for (String value : searchableValues.values()) {
                if (value.contains(normalizedQuery)) {
                    return true;
                }
            }
            return false;
        }

        private void ensureSearchableValues() {
            if (!searchableValues.isEmpty()) {
                return;
            }
            searchableValues.put("id", normalize(id));
            searchableValues.put("className", normalize(className));
            searchableValues.put("simpleClassName", normalize(simpleClassName));
            searchableValues.put("rootFolder", normalize(rootFolder));
            searchableValues.put("displayName", normalize(displayName(simpleClassName)));
        }

        @Override
        public String toString() {
            return id + " - " + displayName(simpleClassName);
        }
    }

    private static final class TextAreaOutputStream extends OutputStream {
        private final JTextArea textArea;
        private final StringBuilder buffer = new StringBuilder();

        private TextAreaOutputStream(JTextArea textArea) {
            this.textArea = textArea;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\r') {
                return;
            }

            buffer.append((char) b);
            if (b == '\n') {
                flush();
            }
        }

        @Override
        public synchronized void flush() {
            if (buffer.length() == 0) {
                return;
            }

            String text = buffer.toString();
            buffer.setLength(0);
            appendOutput(textArea, text);
        }
    }
}
