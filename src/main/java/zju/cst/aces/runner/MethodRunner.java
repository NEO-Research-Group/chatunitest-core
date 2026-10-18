package zju.cst.aces.runner;

import zju.cst.aces.api.phase.Phase;
import zju.cst.aces.api.phase.PhaseImpl;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.impl.PromptConstructorImpl;
import zju.cst.aces.dto.*;
import zju.cst.aces.mutation.Mutant;
import zju.cst.aces.mutation.MutantSelector;
import zju.cst.aces.mutation.MutationAnalyzer;
import zju.cst.aces.mutation.MutationRequest;
import zju.cst.aces.mutation.MutationResult;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class MethodRunner extends ClassRunner {

    public MethodInfo methodInfo;

    public MethodRunner(Config config, String fullClassName, MethodInfo methodInfo) throws IOException {
        super(config, fullClassName);
        this.methodInfo = methodInfo;
    }

    @Override
    public void start() throws IOException {
        if (!config.isStopWhenSuccess() && config.isEnableMultithreading()) {
            ExecutorService executor = Executors.newFixedThreadPool(config.getTestNumber());
            List<Future<String>> futures = new ArrayList<>();
            for (int num = 0; num < config.getTestNumber(); num++) {
                int finalNum = num;
                Callable<String> callable = () -> {
                    startRounds(finalNum);
                    return "";
                };
                Future<String> future = executor.submit(callable);
                futures.add(future);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(executor::shutdownNow));

            for (Future<String> future : futures) {
                try {
                    String result = future.get();
                    System.out.println(result);
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                }
            }

            executor.shutdown();
        } else {
            for (int num = 0; num < config.getTestNumber(); num++) {
                boolean result = startRounds(num); //todo
                if (result && config.isStopWhenSuccess()) {
                    break;
                }
            }
        }
    }

    public boolean startRounds(final int num) throws IOException {

        Phase phase = PhaseImpl.createPhase(config);

        // Prompt Construction Phase
        PromptConstructorImpl pc = phase.generatePrompt(classInfo, methodInfo,num);
        PromptInfo promptInfo = pc.getPromptInfo();

        String sourceCode = promptInfo.methodInfo.sourceCode;
        // Count the number of branches in the method's source code
        int conditionsCount = countConditionsInSource(sourceCode);
        config.getLogger().debug("SOFIA ACTIVATIONS NUMBER: " + promptInfo.sofiaActivations);
        config.getLogger().debug("BRANCH NUMBER: " + conditionsCount);
        if (!passesMethodFilter(config.getMethodFilter(), promptInfo.sofiaActivations, conditionsCount)) {
            config.getLogger().info("Method < " + methodInfo.methodName + " > skipped by methodFilter=" + config.getMethodFilter()
                    + " (externalDeps=" + promptInfo.sofiaActivations + ", branches=" + conditionsCount + ")");
            return false;
        }

        long startTime = System.nanoTime();
        // Phase 1: generation + repair (rounds 1..maxRounds-1), as ChatUniTest
        boolean success = generateAndRepair(phase, pc, false, config.getMaxRounds() - 1) == Outcome.PASSED;
        exportRecord(pc.getPromptInfo(), classInfo, num);

        // Phase 2: quality improvement, only from a green suite. PIT on S0 once; with improvePromptType=both,
        // the mutants and generic arms start from the same S0 (enfoque.md §5), one JSON entry per arm.
        List<Map<String, String>> arms = new ArrayList<>();
        if (success && config.isEnableQualityImprove()) {
            S0Baseline s0 = new S0Baseline(pc);
            for (String arm : improveArms()) {
                arms.add(new QualityLoop(phase, s0, arm, num).run());
            }
        }

        if (config.generateJsonReport) {
            long endTime = System.nanoTime();
            float duration = (float) (endTime - startTime) / 1_000_000_000;
            if (arms.isEmpty()) {
                generateJsonReport(pc.getPromptInfo(), duration, success, null);
            }
            for (Map<String, String> arm : arms) {
                generateJsonReport(pc.getPromptInfo(), duration, success, arm);
            }
        }
        return success;
    }

    /** Arms of the quality improvement: mutants, generic, or both from the same S0. */
    List<String> improveArms() {
        if (Config.IMPROVE_BOTH.equals(config.getImprovePromptType())) {
            return Arrays.asList(Config.IMPROVE_MUTANTS, Config.IMPROVE_GENERIC);
        }
        return Collections.singletonList(config.getImprovePromptType());
    }

    public enum Outcome { PASSED, PARTIAL, FAILED }

    /**
     * Round 0 (generation, or improvement if improve) + validation, and repair + validation up to lastRound.
     * If every round fails, the tests that passed in some round are validated on their own (PARTIAL_CORRECT_TESTS).
     * Used for S0 and for each improvement class Q_i, so that both are repaired in the same way.
     */
    public Outcome generateAndRepair(Phase phase, PromptConstructorImpl pc, boolean improve, int lastRound) {
        PromptInfo promptInfo = pc.getPromptInfo();
        promptInfo.setRound(0);
        if (improve) {
            phase.improveTest(pc);
        } else {
            phase.generateTest(pc);
        }
        if (phase.validateTest(pc)) {
            return Outcome.PASSED;
        }
        for (int rounds = 1; rounds <= lastRound; rounds++) {
            promptInfo.setRound(rounds);
            phase.repairTest(pc);
            if (phase.validateTest(pc)) {
                return Outcome.PASSED;
            }
        }
        // All the rounds failed: keep the tests that passed in some round, if they still pass on their own
        return validateGreenCandidate(config, pc.getFullTestName(), promptInfo) ? Outcome.PARTIAL : Outcome.FAILED;
    }

    /**
     * S0 before the quality improvement (enfoque.md §3.4): PIT on S0 and, if its green run fails, the failing tests
     * of S0 are pruned (as make_s0.sh) and S0 is rewritten. Run once and shared by the arms.
     */
    class S0Baseline {
        final PromptInfo info;
        final String name;
        final Path path;
        String code;
        MutationResult result;
        int pitPruned;
        String error;

        S0Baseline(PromptConstructorImpl pc) {
            info = pc.getPromptInfo();
            name = pc.getFullTestName();
            path = info.getTestPath();
            try {
                code = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                result = pit(info, Collections.singletonMap(name, code), Collections.singleton(name));
                pitPruned = prunedCount(result, name);
                if (ok() && pitPruned > 0) {
                    code = result.getFinalTests().get(name);
                    exportTest(code, path);
                }
            } catch (Exception e) {
                config.getLogger().warn("PIT on the S0 of < " + methodInfo.methodName + " > failed: " + e);
                error = e.toString();
            }
        }

        boolean ok() {
            return error == null && result.getStatus() == MutationResult.Status.OK && result.getFinalTests() != null
                    && result.getFinalTests().containsKey(name);
        }
    }

    /**
     * Quality improvement cycles of one arm (enfoque.md §3.1): from A_0 = S0, each cycle i asks for a new class
     * X_<tag>i_Test with new tests (tag Q in mutants, G in generic), validates and repairs it up to
     * maxRepairPerCycle rounds and runs PIT on A_{i-1} + Q_i, pruning only Q_i. If Q_i is valid, A_i = A_{i-1} + Q_i;
     * if not, it is deleted and A_i = A_{i-1}. A discarded cycle counts. The PIT of A_i is the one the next cycle
     * starts from. S0 and the previous classes are never changed. Returns the fields for the JSON report.
     */
    class QualityLoop {
        final Phase phase;
        final S0Baseline s0;
        final String arm;
        final String tag;
        final int num;
        final Map<String, String> out = new LinkedHashMap<>();
        final MutantSelector selector = new MutantSelector(config.getMaxMutantsPerPrompt(), config.getMaxMutantAttempts());
        final Map<String, String> suite = new LinkedHashMap<>();   // A_i: test class -> code
        long pitMillis;
        int current;   // cycle in progress

        QualityLoop(Phase phase, S0Baseline s0, String arm, int num) {
            this.phase = phase;
            this.s0 = s0;
            this.arm = arm;
            this.tag = Config.IMPROVE_GENERIC.equals(arm) ? "G" : "Q";
            this.num = num;
        }

        Map<String, String> run() {
            out.put("qualityEnabled", "true");
            out.put("improvePromptType", arm);
            out.put("s0Shared", String.valueOf(Config.IMPROVE_BOTH.equals(config.getImprovePromptType())));
            out.put("improveFocalContext", config.getImproveFocalContext());
            out.put("mutators", config.getMutators());
            out.put("maxRepairPerCycle", String.valueOf(config.getMaxRepairPerCycle()));
            out.put("maxQualityCycles", String.valueOf(config.getMaxQualityCycles()));
            out.put("maxMutantsPerPrompt", String.valueOf(config.getMaxMutantsPerPrompt()));
            out.put("maxMutantAttempts", String.valueOf(config.getMaxMutantAttempts()));
            out.put("stagnationCycles", String.valueOf(config.getStagnationCycles()));
            if (s0.error != null) {
                stop("ERROR", 0);
                out.put("qualityError", s0.error);
                return out;
            }
            putResult("s0", s0.result);
            out.put("s0GreenPrunedTests", String.valueOf(s0.pitPruned));
            try {
                cycles();
            } catch (Exception e) {
                config.getLogger().warn("Quality cycles of < " + methodInfo.methodName + " > (" + arm + ") failed: " + e);
                stop("ERROR", current);
                out.put("qualityError", e.toString());
            }
            out.put("pitMillis", String.valueOf(pitMillis + s0.result.getTotalMillis()));
            return out;
        }

        /**
         * Stops at the first of (enfoque.md §3.3): N cycles done (MAX_CYCLES); no SURVIVED mutant left
         * (NO_SURVIVED) or only excluded ones (ALL_EXHAUSTED); stagnationCycles cycles in a row without killing
         * any new mutant (STAGNATION). The generic arm rotates and counts the attempts in the same way, without
         * showing the mutants, so that both arms stop by the same rule.
         */
        void cycles() throws IOException {
            if (!s0.ok()) {
                stop("NO_CYCLE_" + s0.result.getStatus(), 0);
                return;
            }
            suite.put(s0.name, s0.code);
            MutationResult prev = s0.result;
            int withoutKills = 0;
            for (int i = 1; i <= config.getMaxQualityCycles(); i++) {
                current = i;
                if (prev.getSurvived() == 0) {
                    stop("NO_SURVIVED", i - 1);
                    return;
                }
                if (selector.candidates(prev).isEmpty()) {
                    stop("ALL_EXHAUSTED", i - 1);
                    return;
                }
                MutationResult next = cycle(i, selector.select(prev), prev);
                Set<String> killed = detectedIds(next);
                killed.removeAll(detectedIds(prev));
                out.put("c" + i + "_newKilled", String.valueOf(killed.size()));
                withoutKills = killed.isEmpty() ? withoutKills + 1 : 0;
                prev = next;
                if (withoutKills >= config.getStagnationCycles()) {
                    stop("STAGNATION", i);
                    return;
                }
            }
            stop("MAX_CYCLES", config.getMaxQualityCycles());
        }

        void stop(String reason, int cycle) {
            out.put("stopReason", reason);
            out.put("stopCycle", String.valueOf(cycle));
            config.getLogger().info("Quality cycles of < " + methodInfo.methodName + " > (" + arm + "): " + reason
                    + " after " + cycle + " cycles");
        }

        /** Cycle i from A_{i-1} (PIT result prev); returns the PIT result of A_i. */
        MutationResult cycle(int i, List<Mutant> chosen, MutationResult prev) throws IOException {
            String p = "c" + i + "_";
            String qName = s0.name.substring(0, s0.name.length() - "_Test".length()) + "_" + tag + i + "_Test";
            Path qPath = config.getTestOutput().resolve(qName.replace(".", File.separator) + ".java");
            PromptInfo q = PromptInfo.forImprovement(s0.info, qName, qPath);
            q.setImproveMode(arm);
            q.setSuiteTests(String.join("\n\n", suite.values()));
            q.setPruneFromRound(config.getMaxRepairPerCycle());
            String chosenIds = chosen.stream().map(m -> m.id).collect(Collectors.joining(";"));
            if (Config.IMPROVE_MUTANTS.equals(arm)) {
                q.setImproveMutants(MutantSelector.format(chosen, focalSourceLines(s0.info)));
                q.setImproveScore(MutantSelector.score(prev));
                out.put(p + "shownMutants", chosenIds);
            } else {
                out.put(p + "hiddenMutants", chosenIds);   // chosen and counted as attempts, not in the prompt
            }
            if (Config.IMPROVE_CONTEXT_FCE.equals(config.getImproveFocalContext())) {
                q.setImproveFce(classInfo.getCompilationUnitCode());
            } else {
                q.setPrivateBodies(privateBodies(s0.info));
            }
            PromptConstructorImpl qpc = new PromptConstructorImpl(config);
            qpc.setPromptInfo(q);
            qpc.setFullTestName(qName);
            Outcome qo = generateAndRepair(phase, qpc, true, config.getMaxRepairPerCycle());
            putImprovementStats(p, q, qo);
            exportRecord(s0.info, q.getRecords(), classInfo, num, "records_" + tag + i + ".json");
            if (qo == Outcome.FAILED) {
                Files.deleteIfExists(qPath);
                out.put(p + "outcome", "DISCARDED_RED");
                return prev;
            }

            // PIT on A_i = A_{i-1} + Q_i; a failing green run can only prune tests of Q_i
            Map<String, String> tests = new LinkedHashMap<>(suite);
            tests.put(qName, new String(Files.readAllBytes(qPath), StandardCharsets.UTF_8));
            MutationResult r = pit(s0.info, tests, Collections.singleton(qName));
            pitMillis += r.getTotalMillis();
            putResult(p + "a", r);
            out.put(p + "greenPrunedTests", String.valueOf(prunedCount(r, qName)));
            if (r.getStatus() != MutationResult.Status.OK || r.getFinalTests() == null
                    || !r.getFinalTests().containsKey(qName)) {
                Files.deleteIfExists(qPath);
                out.put(p + "outcome", "DISCARDED_PIT_" + r.getStatus());
                return prev;
            }
            if (prunedCount(r, qName) > 0) {
                exportTest(r.getFinalTests().get(qName), qPath);
            }
            suite.put(qName, r.getFinalTests().get(qName));
            Set<String> chosenSet = chosen.stream().map(m -> m.id).collect(Collectors.toSet());
            long killedChosen = r.getMutants().stream().filter(m -> m.detected && chosenSet.contains(m.id)).count();
            out.put(p + "killedChosen", String.valueOf(killedChosen));
            out.put(p + "outcome", qo == Outcome.PARTIAL ? "VALID_PARTIAL"
                    : "PASSED_AFTER_PRUNE".equals(q.getValidationOutcome()) ? "VALID_PRUNED" : "VALID_PASSED");
            return r;
        }

        void putResult(String prefix, MutationResult r) {
            out.put(prefix + "PitStatus", String.valueOf(r.getStatus()));
            out.put(prefix + "Mutants", String.valueOf(r.getTotal()));
            out.put(prefix + "Detected", String.valueOf(r.getDetected()));
            out.put(prefix + "Survived", String.valueOf(r.getSurvived()));
            out.put(prefix + "NoCoverage", String.valueOf(r.getNoCoverage()));
            out.put(prefix + "MS", String.valueOf(r.getMs()));
            out.put(prefix + "TS", String.valueOf(r.getTs()));
        }

        void putImprovementStats(String p, PromptInfo q, Outcome qo) {
            int improveIn = 0, improveOut = 0, repairIn = 0, repairOut = 0;
            for (RoundRecord r : q.getRecords()) {
                int in = r.getPromptToken() + r.getDiscardedPromptToken();
                int o = r.getResponseToken() + r.getDiscardedResponseToken();
                if (r.getRound() == 0) {
                    improveIn += in;
                    improveOut += o;
                } else {
                    repairIn += in;
                    repairOut += o;
                }
            }
            out.put(p + "validation", qo + "/" + q.getValidationOutcome());
            out.put(p + "rounds", String.valueOf(q.getRecords().size() - 1));
            out.put(p + "inRoundRestarts", String.valueOf(q.getInRoundRestarts()));
            out.put(p + "stagnantRounds", String.valueOf(q.getStagnantRounds()));
            out.put(p + "testsExported", String.valueOf(q.getTestsExported()));
            out.put(p + "testsPruned", String.valueOf(q.getTestsPruned()));
            out.put(p + "truncatedResponses", String.valueOf(q.countTruncatedResponses(config.getMaxResponseTokens())));
            out.put(p + "improveInputTokens", String.valueOf(improveIn));
            out.put(p + "improveOutputTokens", String.valueOf(improveOut));
            out.put(p + "repairInputTokens", String.valueOf(repairIn));
            out.put(p + "repairOutputTokens", String.valueOf(repairOut));
        }
    }

    /** PIT on the given test classes (FQN -> code), pruning failing tests of the prunable classes only. */
    MutationResult pit(PromptInfo s0, Map<String, String> tests, Set<String> prunable) {
        MutationRequest req = MutationRequest.from(config, s0, tests);
        req.setMutators(config.getMutators());
        req.setProcessTimeoutSeconds(config.getPitTimeout());
        MutationResult r = new MutationAnalyzer().analyzeWithGreenPruning(req, MAX_GREEN_PRUNES, prunable);
        if (!config.isKeepPitWork()) {
            try {
                MutationAnalyzer.deleteRecursively(req.getWorkDir());
            } catch (IOException e) {
                config.getLogger().warn("Cannot delete " + req.getWorkDir() + ": " + e);
            }
        }
        config.getLogger().info("PIT on " + tests.keySet() + ": " + r.getStatus() + ", " + r.getDetected() + "/"
                + r.getTotal() + " detected, " + r.getSurvived() + " survived (" + r.getTotalMillis() + " ms)");
        if (r.getStatus() != MutationResult.Status.OK) {
            config.getLogger().warn("PIT log tail:\n" + r.getLogTail() + "\n" + String.join("\n", r.getCompileErrors()));
        }
        return r;
    }

    static Set<String> detectedIds(MutationResult r) {
        return r.getMutants().stream().filter(m -> m.detected).map(m -> m.id).collect(Collectors.toCollection(HashSet::new));
    }

    static int prunedCount(MutationResult r, String testClass) {
        Set<String> pruned = r.getPrunedTests() == null ? null : r.getPrunedTests().get(testClass);
        return pruned == null ? 0 : pruned.size();
    }

    /** Lines of the source file of the focal class (PIT's line numbers), or null if it is not found. */
    List<String> focalSourceLines(PromptInfo promptInfo) {
        String rel = classInfo.getPackageName() == null || classInfo.getPackageName().isEmpty() ? ""
                : classInfo.getPackageName().replace(".", File.separator) + File.separator;
        String top = promptInfo.getFullClassName().substring(promptInfo.getFullClassName().lastIndexOf('.') + 1);
        if (top.contains("$")) {
            top = top.substring(0, top.indexOf('$'));
        }
        for (String root : config.getProject().getCompileSourceRoots()) {
            Path p = Paths.get(root, rel + top + ".java");
            if (Files.exists(p)) {
                try {
                    return Files.readAllLines(p, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    config.getLogger().warn("Cannot read " + p + ": " + e);
                }
            }
        }
        return null;
    }

    /**
     * Source code of the private methods of the focal class called (without scope or with this) by the focal
     * method. S05: Q did not compile when the prompt gave only their signatures (stubs/verify of private methods).
     */
    String privateBodies(PromptInfo promptInfo) {
        try {
            com.github.javaparser.ast.body.MethodDeclaration focal =
                    com.github.javaparser.StaticJavaParser.parseMethodDeclaration(promptInfo.getMethodInfo().sourceCode);
            Set<String> called = new LinkedHashSet<>();
            for (com.github.javaparser.ast.expr.MethodCallExpr call : focal.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class)) {
                if (!call.getScope().isPresent() || call.getScope().get().isThisExpr()) {
                    called.add(call.getNameAsString());
                }
            }
            if (called.isEmpty()) {
                return "";
            }
            com.github.javaparser.ast.CompilationUnit cu = new com.github.javaparser.JavaParser()
                    .parse(classInfo.getCompilationUnitCode()).getResult().orElse(null);
            if (cu == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (com.github.javaparser.ast.body.MethodDeclaration m : cu.findAll(com.github.javaparser.ast.body.MethodDeclaration.class)) {
                if (m.isPrivate() && called.contains(m.getNameAsString())) {
                    sb.append(m.toString()).append("\n\n");
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            config.getLogger().warn("Cannot extract the private methods called by < " + methodInfo.methodName + " >: " + e);
            return "";
        }
    }

    /**
     * Method selection. "benchmark" (default) reproduces the Expert Systems benchmark: the method must use
     * at least one external dependency (SOFIA activations) and have at least one branch.
     */
    public static boolean passesMethodFilter(String methodFilter, int externalDeps, int branches) {
        switch (methodFilter) {
            case Config.METHOD_FILTER_NONE:
                return true;
            case Config.METHOD_FILTER_BRANCHES:
                return branches > 0;
            case Config.METHOD_FILTER_EXTERNAL_DEPS:
                return externalDeps > 0;
            case Config.METHOD_FILTER_BENCHMARK:
            default:
                return externalDeps > 0 && branches > 0;
        }
    }

    public static int countConditionsInSource(String methodSource) {
        com.github.javaparser.ast.body.MethodDeclaration method;
        try {
            method = com.github.javaparser.StaticJavaParser.parseMethodDeclaration(methodSource);
        } catch (Exception e) {
            return 0;
        }
        AtomicInteger count = new AtomicInteger();
        // Only consider nodes that are direct or indirect children of the main method body, but not inside inner method declarations
        if (method.getBody().isPresent()) {
            com.github.javaparser.ast.stmt.BlockStmt body = method.getBody().get();
            // Use a visitor to skip nested method declarations
            body.walk(node -> {
                // Skip if inside a nested method or lambda
                if (node.findAncestor(com.github.javaparser.ast.body.MethodDeclaration.class)
                        .filter(ancestor -> ancestor != method).isPresent()
                        || node.findAncestor(com.github.javaparser.ast.expr.LambdaExpr.class).isPresent()) {
                    return;
                }
                if (node instanceof com.github.javaparser.ast.stmt.IfStmt
                        || node instanceof com.github.javaparser.ast.stmt.SwitchEntry
                        || node instanceof com.github.javaparser.ast.stmt.ForStmt
                        || node instanceof com.github.javaparser.ast.stmt.WhileStmt
                        || node instanceof com.github.javaparser.ast.stmt.DoStmt
                        || node instanceof com.github.javaparser.ast.stmt.ForEachStmt) {
                    count.getAndIncrement();
                }
                if (node instanceof com.github.javaparser.ast.expr.BinaryExpr) {
                    com.github.javaparser.ast.expr.BinaryExpr.Operator op =
                            ((com.github.javaparser.ast.expr.BinaryExpr) node).getOperator();
                    if (op == com.github.javaparser.ast.expr.BinaryExpr.Operator.AND
                            || op == com.github.javaparser.ast.expr.BinaryExpr.Operator.OR) {
                        count.getAndIncrement();
                    }
                }
                if (node instanceof com.github.javaparser.ast.expr.ConditionalExpr) {
                    count.getAndIncrement();
                }
                if (node instanceof com.github.javaparser.ast.expr.UnaryExpr) {
                    com.github.javaparser.ast.expr.UnaryExpr.Operator op = ((com.github.javaparser.ast.expr.UnaryExpr) node).getOperator();
                    if (op == com.github.javaparser.ast.expr.UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
                        count.getAndIncrement();
                    }
                }
            });
        }
        return count.get();
    }
}