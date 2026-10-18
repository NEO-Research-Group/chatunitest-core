package zju.cst.aces.mutation;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import zju.cst.aces.util.TestProcessor;

import javax.tools.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Runs PIT on a set of test classes and returns the mutants of the focal method.
 *
 * PIT (pitest-command-line, a runtime dependency of the core) runs in a child JVM, without Maven: the core is
 * compiled for Java 8 and PIT 1.30.1 needs Java 11, and the child keeps PIT's classpath apart from the plugin's.
 * The test classes are compiled together in a directory of the call, so several classes (S0 + Q_1..Q_i) can be
 * analysed at once and nothing is shared with other methods. The metrics are those of
 * scripts/parse_mutations.py in CUTMUT (S02/S03): filter by mutatedMethod + methodDescription,
 * detected = KILLED + TIMED_OUT + MEMORY_ERROR + RUN_ERROR (+ NON_VIABLE), MS and TS.
 */
public class MutationAnalyzer {

    public static final String PIT_MAIN = "org.pitest.mutationtest.commandline.MutationCoverageReport";
    /** One class of each jar that PIT needs on its launch classpath. */
    private static final String[] PIT_ANCHORS = {
            "org/pitest/mutationtest/commandline/MutationCoverageReport.class",  // pitest-command-line
            "org/pitest/mutationtest/tooling/EntryPoint.class",                  // pitest-entry
            "org/pitest/mutationtest/engine/gregor/GregorMutater.class",         // pitest
            "org/pitest/junit5/JUnit5TestPluginFactory.class",                   // pitest-junit5-plugin
            "org/apache/commons/text/StringEscapeUtils.class",                   // commons-text (XML report)
            "org/apache/commons/lang3/StringUtils.class",                        // commons-lang3 (commons-text)
    };
    private static final Set<String> DETECTED =
            new HashSet<>(Arrays.asList("KILLED", "TIMED_OUT", "MEMORY_ERROR", "RUN_ERROR", "NON_VIABLE"));
    private static final Pattern TEST_ID = Pattern.compile("\\[class:([^\\]]+)\\]/\\[(?:method|test-template):([^(\\]]+)\\(");
    private static final Pattern NOT_GREEN = Pattern.compile("testClass=([^,\\]\\s]+).*did not pass without mutation");
    private static final Pattern ENGINE_JAR = Pattern.compile("junit-platform-engine-([^/\\\\]+)\\.jar$");

    /** Explicit classpath for PIT (system property cutmut.pit.classpath, path-separated); null = locate it. */
    public static List<String> pitClasspath;

    public MutationResult analyze(MutationRequest req) {
        long t0 = System.currentTimeMillis();
        MutationResult res = new MutationResult();
        res.mutators = req.mutators;
        try {
            res = run(req, res);
        } catch (Exception e) {
            res.status = MutationResult.Status.PIT_ERROR;
            res.logTail = e.toString();
        }
        res.totalMillis = System.currentTimeMillis() - t0;
        return res;
    }

    /**
     * analyze() and, while the green run fails, remove the failing tests by name and run again, up to maxPrunes
     * times (as AbstractRunner.validateGreenCandidate). Only the classes in prunable can be pruned (null = all);
     * a failure in another class, or one that is not attributed to a test method, ends with GREEN_RUN_FAILED.
     * A class left without tests is dropped. finalTests has the sources that were analysed at the end.
     */
    public MutationResult analyzeWithGreenPruning(MutationRequest req, int maxPrunes, Set<String> prunable) {
        Map<String, String> tests = new LinkedHashMap<>(req.tests);
        Map<String, Set<String>> pruned = new LinkedHashMap<>();
        MutationResult res = null;
        for (int i = 0; i <= maxPrunes; i++) {
            req.tests = tests;
            res = analyze(req);
            res.greenPrunes = i;
            if (res.status != MutationResult.Status.GREEN_RUN_FAILED || i == maxPrunes || !res.failuresAttributed) {
                break;
            }
            if (prunable != null && !prunable.containsAll(res.failingTests.keySet())) {
                break;
            }
            for (Map.Entry<String, Set<String>> e : res.failingTests.entrySet()) {
                String code = tests.get(e.getKey());
                if (code == null) {
                    continue;
                }
                String kept = new TestProcessor(e.getKey()).removeTestsByName(code, e.getValue());
                if (kept == null) {
                    tests.remove(e.getKey());
                } else {
                    tests.put(e.getKey(), kept);
                }
                pruned.computeIfAbsent(e.getKey(), k -> new TreeSet<>()).addAll(e.getValue());
            }
            if (tests.isEmpty()) {
                res.status = MutationResult.Status.EMPTY;
                break;
            }
        }
        res.prunedTests = pruned;
        res.finalTests = tests;
        return res;
    }

    private MutationResult run(MutationRequest req, MutationResult res) throws Exception {
        // Classes without any @Test are not run; with none left, the suite is empty
        Map<String, String> tests = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : req.tests.entrySet()) {
            if (new TestProcessor(e.getKey()).countTestCases(e.getValue()) != 0) {
                tests.put(e.getKey(), e.getValue());
            }
        }
        res.testClasses.addAll(tests.keySet());
        if (tests.isEmpty()) {
            res.status = MutationResult.Status.EMPTY;
            return res;
        }

        Path work = req.workDir.toAbsolutePath();
        Path classes = work.resolve("classes");
        Path reports = work.resolve("pit");
        deleteRecursively(work);
        Files.createDirectories(classes);
        Files.createDirectories(reports);
        res.reportDir = reports.toString();

        List<String> cp = new ArrayList<>();
        cp.add(classes.toString());
        cp.add(req.targetClassesDir);
        for (String e : req.classPath) {
            if (!cp.contains(e)) {
                cp.add(e);
            }
        }

        long t = System.currentTimeMillis();
        boolean compiled = compile(tests, cp, classes, res.compileErrors);
        res.compileMillis = System.currentTimeMillis() - t;
        if (!compiled) {
            res.status = MutationResult.Status.COMPILE_ERROR;
            return res;
        }

        String launcher = findLauncher(cp);
        if (launcher != null && !cp.contains(launcher)) {
            cp.add(launcher);
        }
        Path cpFile = work.resolve("classpath.txt");
        Files.write(cpFile, cp, StandardCharsets.UTF_8);

        String focal = req.focalClass;
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(req.javaHome != null ? req.javaHome : System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(String.join(File.pathSeparator, locatePitClasspath()));
        cmd.add(PIT_MAIN);
        Collections.addAll(cmd,
                "--reportDir", reports.toString(),
                "--targetClasses", focal + "," + focal + "$*",
                "--targetTests", String.join(",", tests.keySet()),
                "--classPathFile", cpFile.toString(),
                "--mutableCodePaths", req.targetClassesDir,
                "--mutators", req.mutators,
                "--outputFormats", "XML",
                "--timestampedReports=false",
                "--fullMutationMatrix=true",
                "--threads", String.valueOf(req.threads));
        if (req.sourceDir != null) {
            Collections.addAll(cmd, "--sourceDirs", req.sourceDir);
        }
        if (req.timeoutConst != null) {
            Collections.addAll(cmd, "--timeoutConst", String.valueOf(req.timeoutConst));
        }
        Files.write(work.resolve("cmd.txt"), Collections.singletonList(String.join(" ", cmd)), StandardCharsets.UTF_8);

        Path log = work.resolve("pit.log");
        t = System.currentTimeMillis();
        Process p = new ProcessBuilder(cmd).directory(work.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        boolean finished = p.waitFor(req.processTimeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            p.waitFor(30, TimeUnit.SECONDS);
        }
        res.pitMillis = System.currentTimeMillis() - t;
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        res.logTail = lines.subList(Math.max(0, lines.size() - 30), lines.size()).stream().collect(Collectors.joining("\n"));
        if (!finished) {
            res.status = MutationResult.Status.TIMEOUT;
            res.exitCode = -1;
            return res;
        }
        res.exitCode = p.exitValue();

        Path xml = reports.resolve("mutations.xml");
        if (res.exitCode != 0 || !Files.exists(xml)) {
            if (collectNotGreen(lines, res)) {
                res.status = MutationResult.Status.GREEN_RUN_FAILED;
            } else {
                res.status = MutationResult.Status.PIT_ERROR;
            }
            return res;
        }
        parseMutations(xml, req, res);
        res.status = MutationResult.Status.OK;
        return res;
    }

    // ---------------------------------------------------------------- compilation

    private boolean compile(Map<String, String> tests, List<String> cp, Path out, List<String> errors) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            errors.add("No Java compiler available (running on a JRE?)");
            return false;
        }
        List<JavaFileObject> units = new ArrayList<>();
        for (Map.Entry<String, String> e : tests.entrySet()) {
            final String code = e.getValue();
            units.add(new SimpleJavaFileObject(URI.create("string:///" + e.getKey().replace('.', '/') + ".java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return code;
                }
            });
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            List<String> options = Arrays.asList("-classpath", String.join(File.pathSeparator, cp),
                    "-d", out.toString(), "-nowarn", "-proc:none");
            boolean ok = compiler.getTask(null, fm, diagnostics, options, null, units).call();
            if (!ok) {
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    if (d.getKind() == Diagnostic.Kind.ERROR) {
                        String src = d.getSource() == null ? "" : d.getSource().getName();
                        errors.add(src + ":" + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT));
                    }
                }
            }
            return ok;
        }
    }

    // ---------------------------------------------------------------- classpaths

    /** Jars of PIT for the launch classpath: cutmut.pit.classpath, pitClasspath, or located from the anchors. */
    static List<String> locatePitClasspath() throws IOException {
        String prop = System.getProperty("cutmut.pit.classpath");
        if (prop != null && !prop.isEmpty()) {
            return Arrays.asList(prop.split(File.pathSeparator));
        }
        if (pitClasspath != null) {
            return pitClasspath;
        }
        List<String> jars = new ArrayList<>();
        ClassLoader cl = MutationAnalyzer.class.getClassLoader();
        for (String anchor : PIT_ANCHORS) {
            URL url = cl.getResource(anchor);
            if (url == null) {
                throw new IOException("PIT not found on the classpath (" + anchor + "): add pitest-command-line and "
                        + "pitest-junit5-plugin, or set -Dcutmut.pit.classpath");
            }
            String jar;
            if ("jar".equals(url.getProtocol())) {
                jar = Paths.get(URI.create(((JarURLConnection) url.openConnection()).getJarFileURL().toString())).toString();
            } else {
                // directory on the classpath: strip the resource path
                String path = Paths.get(URI.create(url.toString())).toString();
                jar = path.substring(0, path.length() - anchor.length() - 1);
            }
            if (!jars.contains(jar)) {
                jars.add(jar);
            }
        }
        return jars;
    }

    /**
     * pitest-junit5-plugin 1.2.x needs junit-platform-launcher on the test classpath, of the same version as the
     * JUnit Platform of the project (pitest-maven adds it; the command line does not). If the classpath has no
     * launcher, it is looked up next to junit-platform-engine in the local Maven repository.
     */
    static String findLauncher(List<String> cp) {
        for (String e : cp) {
            if (Paths.get(e).getFileName().toString().startsWith("junit-platform-launcher-")) {
                return null;
            }
        }
        for (String e : cp) {
            Matcher m = ENGINE_JAR.matcher(e);
            if (m.find()) {
                String v = m.group(1);
                // .../org/junit/platform/junit-platform-engine/<v>/junit-platform-engine-<v>.jar
                Path launcher = Paths.get(e).getParent().getParent().getParent()
                        .resolve("junit-platform-launcher").resolve(v).resolve("junit-platform-launcher-" + v + ".jar");
                if (Files.exists(launcher)) {
                    return launcher.toString();
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- green run

    /** Tests that do not pass without mutation, from PIT's log. Returns false if there are none. */
    static boolean collectNotGreen(List<String> lines, MutationResult res) {
        boolean any = false;
        for (String line : lines) {
            Matcher m = NOT_GREEN.matcher(line);
            if (!m.find()) {
                continue;
            }
            any = true;
            Matcher t = TEST_ID.matcher(line);
            if (t.find()) {
                res.failingTests.computeIfAbsent(t.group(1), k -> new TreeSet<>()).add(t.group(2));
            } else {
                res.failuresAttributed = false;
                res.failingTests.computeIfAbsent(m.group(1), k -> new TreeSet<>());
            }
        }
        return any;
    }

    // ---------------------------------------------------------------- mutations.xml

    static void parseMutations(Path xml, MutationRequest req, MutationResult res) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        Document doc = dbf.newDocumentBuilder().parse(xml.toFile());
        NodeList nodes = doc.getDocumentElement().getElementsByTagName("mutation");
        String focal = req.focalClass;

        List<Element> cls = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element u = (Element) nodes.item(i);
            String mc = text(u, "mutatedClass");
            if (mc.equals(focal) || mc.startsWith(focal + "$")) {
                cls.add(u);
            }
        }
        res.clsTotal = cls.size();
        res.clsDetected = (int) cls.stream().filter(u -> DETECTED.contains(u.getAttribute("status"))).count();
        res.clsNoCoverage = (int) cls.stream().filter(u -> "NO_COVERAGE".equals(u.getAttribute("status"))).count();

        List<Element> byName = cls.stream().filter(u -> req.methodName.equals(text(u, "mutatedMethod")))
                .collect(Collectors.toList());
        Set<String> descs = new TreeSet<>();
        byName.forEach(u -> descs.add(text(u, "methodDescription")));
        res.overloads = descs.size();
        String desc = pickDescriptor(descs, req.methodDescriptor, req.methodSignature);
        if (desc == null && descs.size() > 1) {
            res.descOk = false;
        }
        res.methodDescription = desc == null ? "" : desc;

        for (Element u : byName) {
            if (desc != null && !desc.equals(text(u, "methodDescription"))) {
                continue;
            }
            Mutant m = toMutant(u);
            res.mutants.add(m);
            switch (m.status) {
                case "KILLED": res.killed++; break;
                case "SURVIVED": res.survived++; break;
                case "NO_COVERAGE": res.noCoverage++; break;
                case "TIMED_OUT": res.timedOut++; break;
                case "MEMORY_ERROR": res.memoryError++; break;
                case "RUN_ERROR": res.runError++; break;
                case "NON_VIABLE": res.nonViable++; break;
                default: res.other++;
            }
            if (m.detected) {
                res.detected++;
            }
        }
        res.total = res.mutants.size();
        res.ms = res.total > 0 ? (double) res.detected / res.total : null;
        res.ts = res.detected + res.survived > 0 ? (double) res.detected / (res.detected + res.survived) : null;
    }

    private static Mutant toMutant(Element u) {
        Mutant m = new Mutant();
        m.mutatedClass = text(u, "mutatedClass");
        m.mutatedMethod = text(u, "mutatedMethod");
        m.methodDescription = text(u, "methodDescription");
        m.line = Integer.parseInt(text(u, "lineNumber").trim());
        m.mutator = text(u, "mutator");
        m.mutatorShort = m.mutator.substring(m.mutator.lastIndexOf('.') + 1);
        m.description = text(u, "description");
        List<String> idx = new ArrayList<>();
        NodeList in = u.getElementsByTagName("index");
        for (int i = 0; i < in.getLength(); i++) {
            idx.add(in.item(i).getTextContent().trim());
        }
        m.indexes = String.join(",", idx);
        m.status = u.getAttribute("status");
        m.detected = DETECTED.contains(m.status);
        m.killingTests = testNames(text(u, "killingTests"));
        Set<String> covering = new LinkedHashSet<>(m.killingTests);
        covering.addAll(testNames(text(u, "succeedingTests")));
        covering.addAll(testNames(text(u, "coveringTests")));
        m.coveringCount = covering.size();
        m.id = String.join("|", m.mutatedClass, m.mutatedMethod, m.methodDescription, String.valueOf(m.line),
                m.mutator, m.indexes);
        return m;
    }

    /** "pkg.T.[engine:junit-jupiter]/[class:pkg.T]/[method:m()]|..." -> ["pkg.T#m", ...] */
    static List<String> testNames(String field) {
        List<String> out = new ArrayList<>();
        if (field == null || field.isEmpty()) {
            return out;
        }
        for (String t : field.split("\\|")) {
            if (t.isEmpty()) {
                continue;
            }
            Matcher m = TEST_ID.matcher(t);
            String name = m.find() ? m.group(1) + "#" + m.group(2) : t;
            if (!out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    private static String text(Element u, String tag) {
        NodeList l = u.getElementsByTagName(tag);
        return l.getLength() == 0 ? "" : l.item(0).getTextContent();
    }

    // ---------------------------------------------------------------- overloads (as parse_mutations.py)

    private static final Map<Character, String> PRIM = new HashMap<>();
    static {
        PRIM.put('I', "int"); PRIM.put('J', "long"); PRIM.put('Z', "boolean"); PRIM.put('B', "byte");
        PRIM.put('C', "char"); PRIM.put('S', "short"); PRIM.put('F', "float"); PRIM.put('D', "double");
        PRIM.put('V', "void");
    }

    /**
     * Overload of the focal method among the descriptors of PIT: the given descriptor if it is one of them, the
     * only one, or the one whose parameters match the signature (simple type names; type variables match any
     * reference). Null if it is not unique.
     */
    static String pickDescriptor(Set<String> descs, String descriptor, String signature) {
        if (descriptor != null && descs.contains(descriptor)) {
            return descriptor;
        }
        if (descs.size() == 1) {
            return descs.iterator().next();
        }
        if (signature == null || !signature.contains("(")) {
            return null;
        }
        List<String> sp = sigParams(signature);
        List<String> ok = descs.stream().filter(d -> paramsMatch(sp, descParams(d))).collect(Collectors.toList());
        return ok.size() == 1 ? ok.get(0) : null;
    }

    static List<String> descParams(String desc) {
        List<String> out = new ArrayList<>();
        String s = desc.substring(1, desc.indexOf(')'));
        int i = 0;
        while (i < s.length()) {
            int dims = 0;
            while (s.charAt(i) == '[') {
                dims++;
                i++;
            }
            String t;
            if (s.charAt(i) == 'L') {
                int j = s.indexOf(';', i);
                t = s.substring(i + 1, j);
                t = t.substring(t.lastIndexOf('/') + 1);
                t = t.substring(t.lastIndexOf('$') + 1);
                i = j + 1;
            } else {
                t = PRIM.get(s.charAt(i));
                i++;
            }
            StringBuilder b = new StringBuilder(t);
            for (int k = 0; k < dims; k++) {
                b.append("[]");
            }
            out.add(b.toString());
        }
        return out;
    }

    static List<String> sigParams(String sig) {
        String inner = sig.substring(sig.indexOf('(') + 1, sig.lastIndexOf(')'));
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char ch : inner.toCharArray()) {
            if (ch == '<' || ch == '(' || ch == '[') depth++;
            else if (ch == '>' || ch == ')' || ch == ']') depth--;
            if (ch == ',' && depth == 0) {
                out.add(normType(cur.toString()));
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (cur.toString().trim().length() > 0) {
            out.add(normType(cur.toString()));
        }
        return out;
    }

    static String normType(String t) {
        t = t.replaceAll("@\\w+(\\([^)]*\\))?", "").replace("final ", "").trim();
        int dims = countOf(t, "[]") + (t.endsWith("...") ? 1 : 0);
        t = t.replaceAll("<.*>", "").replace("[]", "").replace("...", "").trim();
        t = t.substring(t.lastIndexOf('.') + 1);
        StringBuilder b = new StringBuilder(t);
        for (int k = 0; k < dims; k++) {
            b.append("[]");
        }
        return b.toString();
    }

    private static int countOf(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) {
            n++;
        }
        return n;
    }

    static boolean paramsMatch(List<String> src, List<String> jvm) {
        if (src.size() != jvm.size()) {
            return false;
        }
        for (int i = 0; i < src.size(); i++) {
            String s = src.get(i);
            String j = jvm.get(i);
            if (s.equals(j)) {
                continue;
            }
            boolean typeVar = s.matches("[A-Z][A-Z0-9]?(\\[\\])*");
            String jBase = j.replace("[]", "");
            if (typeVar && countOf(s, "[]") == countOf(j, "[]") && !PRIM.containsValue(jBase)) {
                continue;
            }
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- utils

    public static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        List<Path> all = Files.walk(p).sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        for (Path q : all) {
            Files.delete(q);
        }
    }
}
