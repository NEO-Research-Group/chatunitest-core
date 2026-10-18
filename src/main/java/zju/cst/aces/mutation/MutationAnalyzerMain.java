package zju.cst.aces.mutation;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Command line for MutationAnalyzer (reproducible check of S08 in mallba7, outside ChatUniTest).
 *
 * <pre>
 * java -cp &lt;core + deps&gt; zju.cst.aces.mutation.MutationAnalyzerMain
 *   --cp-file cp.txt --classes proj/target/classes [--src proj/src/main/java]
 *   --focal pkg.Clase --method m [--sig "m(int, String)"] [--desc "(ILjava/lang/String;)V"]
 *   [--mutators STRONGER] [--threads 2] [--timeout-const ms] [--prune-green 3]
 *   --work dir --out result.json --test T1.java [--test T2.java ...]
 * </pre>
 * cp-file: the project classpath, one entry per line or path-separated (mvn dependency:build-classpath).
 * The test class name is the file name without ".java" and without the "_S0" suffix (as run_pit.sh).
 * With --prune-green N, failing tests in the green run are removed by name up to N times (any class).
 */
public class MutationAnalyzerMain {

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    public static void main(String[] args) throws Exception {
        MutationRequest req = new MutationRequest();
        List<String> testFiles = new ArrayList<>();
        Path out = null;
        int pruneGreen = -1;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            String v = i + 1 < args.length ? args[i + 1] : null;
            switch (a) {
                case "--cp-file": req.classPath = readClasspath(Paths.get(v)); i++; break;
                case "--classes": req.targetClassesDir = v; i++; break;
                case "--src": req.sourceDir = v; i++; break;
                case "--focal": req.focalClass = v; i++; break;
                case "--method": req.methodName = v; i++; break;
                case "--sig": req.methodSignature = v; i++; break;
                case "--desc": req.methodDescriptor = v; i++; break;
                case "--mutators": req.mutators = v; i++; break;
                case "--threads": req.threads = Integer.parseInt(v); i++; break;
                case "--timeout-const": req.timeoutConst = Integer.valueOf(v); i++; break;
                case "--process-timeout": req.processTimeoutSeconds = Long.parseLong(v); i++; break;
                case "--java-home": req.javaHome = v; i++; break;
                case "--work": req.workDir = Paths.get(v); i++; break;
                case "--out": out = Paths.get(v); i++; break;
                case "--test": testFiles.add(v); i++; break;
                case "--prune-green": pruneGreen = Integer.parseInt(v); i++; break;
                default: throw new IllegalArgumentException("Unknown option: " + a);
            }
        }
        if (req.focalClass == null || req.methodName == null || req.targetClassesDir == null || req.workDir == null
                || testFiles.isEmpty()) {
            System.err.println("Missing --focal, --method, --classes, --work or --test");
            System.exit(2);
        }
        for (String f : testFiles) {
            String code = new String(Files.readAllBytes(Paths.get(f)), StandardCharsets.UTF_8);
            String cls = Paths.get(f).getFileName().toString().replaceAll("\\.java$", "").replaceAll("_S0$", "");
            Matcher m = PACKAGE.matcher(code);
            req.tests.put(m.find() ? m.group(1) + "." + cls : cls, code);
        }

        MutationAnalyzer analyzer = new MutationAnalyzer();
        MutationResult res = pruneGreen >= 0 ? analyzer.analyzeWithGreenPruning(req, pruneGreen, null)
                : analyzer.analyze(req);
        if (res.finalTests != null) {
            // Only the names of the classes; the pruned sources go to the work directory
            for (java.util.Map.Entry<String, String> e : res.finalTests.entrySet()) {
                Files.write(req.workDir.resolve(e.getKey() + ".pruned.java"),
                        Collections.singletonList(e.getValue()), StandardCharsets.UTF_8);
            }
            res.finalTests = null;
        }
        String json = res.toJson();
        if (out != null) {
            Files.write(out, json.getBytes(StandardCharsets.UTF_8));
        } else {
            System.out.println(json);
        }
        System.err.println(String.format("%s: %s total=%d K=%d S=%d NC=%d TO=%d MS=%s TS=%s pit=%.1fs total=%.1fs",
                req.focalClass + "." + req.methodName, res.status, res.total, res.killed, res.survived, res.noCoverage,
                res.timedOut, res.ms, res.ts, res.pitMillis / 1000.0, res.totalMillis / 1000.0));
    }

    private static List<String> readClasspath(Path f) throws Exception {
        List<String> cp = new ArrayList<>();
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            for (String e : line.split(File.pathSeparator)) {
                if (!e.trim().isEmpty()) {
                    cp.add(e.trim());
                }
            }
        }
        return cp;
    }
}
