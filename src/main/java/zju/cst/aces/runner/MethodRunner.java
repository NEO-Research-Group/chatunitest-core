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

        // Phase 2: quality improvement, only from a green suite
        Map<String, String> quality = null;
        if (success && config.isEnableQualityImprove()) {
            quality = new QualityCycle(phase, pc, num).run();
        }

        exportRecord(pc.getPromptInfo(), classInfo, num);
        if (config.generateJsonReport) {
            long endTime = System.nanoTime();
            float duration = (float) (endTime - startTime) / 1_000_000_000;
            generateJsonReport(pc.getPromptInfo(), duration, success, quality);
        }
        return success;
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
     * One cycle of quality improvement (enfoque.md §3.1, N = 1; the loop of N cycles is S09b):
     * PIT on S0 (green-run pruning of S0, §3.4) -> improvement class Q_1 with new tests -> validation and up to
     * maxRepairPerCycle repairs -> PIT on S0 + Q_1 (pruning only Q_1) -> A_1 = S0 + Q_1, or Q_1 discarded.
     * S0 is never changed by the improvement. Returns the fields for the JSON report.
     */
    class QualityCycle {
        final Phase phase;
        final PromptConstructorImpl s0pc;
        final int num;
        final Map<String, String> out = new LinkedHashMap<>();
        final MutationAnalyzer analyzer = new MutationAnalyzer();
        long pitMillis = 0;

        QualityCycle(Phase phase, PromptConstructorImpl s0pc, int num) {
            this.phase = phase;
            this.s0pc = s0pc;
            this.num = num;
        }

        Map<String, String> run() {
            out.put("qualityEnabled", "true");
            out.put("improvePromptType", config.getImprovePromptType());
            out.put("improveFocalContext", config.getImproveFocalContext());
            out.put("mutators", config.getMutators());
            out.put("maxRepairPerCycle", String.valueOf(config.getMaxRepairPerCycle()));
            try {
                cycle();
            } catch (Exception e) {
                config.getLogger().warn("Quality cycle of < " + methodInfo.methodName + " > failed: " + e);
                out.put("q1Outcome", "ERROR");
                out.put("qualityError", e.toString());
            }
            out.put("pitMillis", String.valueOf(pitMillis));
            return out;
        }

        void cycle() throws IOException {
            PromptInfo s0 = s0pc.getPromptInfo();
            String s0Name = s0pc.getFullTestName();
            Path s0Path = s0.getTestPath();
            String s0Code = new String(Files.readAllBytes(s0Path), StandardCharsets.UTF_8);

            // 1. PIT on S0; if its green run fails, prune the failing tests of S0 (as make_s0.sh)
            Map<String, String> tests = new LinkedHashMap<>();
            tests.put(s0Name, s0Code);
            MutationResult r0 = pit(s0, tests, Collections.singleton(s0Name));
            putResult("s0", r0);
            out.put("s0GreenPrunedTests", String.valueOf(prunedCount(r0, s0Name)));
            if (r0.getStatus() != MutationResult.Status.OK || r0.getFinalTests() == null
                    || !r0.getFinalTests().containsKey(s0Name)) {
                out.put("q1Outcome", "NO_CYCLE_" + r0.getStatus());
                return;
            }
            if (prunedCount(r0, s0Name) > 0) {
                s0Code = r0.getFinalTests().get(s0Name);
                exportTest(s0Code, s0Path);
            }

            // 2. Stop if there are no surviving mutants (same rule in mutants and generic)
            MutantSelector selector = new MutantSelector(config.getMaxMutantsPerPrompt(), config.getMaxMutantAttempts());
            List<Mutant> chosen = selector.select(r0);
            if (chosen.isEmpty()) {
                out.put("q1Outcome", "NO_SURVIVED");
                return;
            }

            // 3. Improvement class Q_1, validated and repaired as S0
            String qName = s0Name.substring(0, s0Name.length() - "_Test".length()) + "_Q1_Test";
            Path qPath = config.getTestOutput().resolve(qName.replace(".", File.separator) + ".java");
            PromptInfo q = PromptInfo.forImprovement(s0, qName, qPath);
            q.setImproveMode(config.getImprovePromptType());
            q.setSuiteTests(s0Code);
            if (Config.IMPROVE_MUTANTS.equals(config.getImprovePromptType())) {
                q.setImproveMutants(MutantSelector.format(chosen, focalSourceLines(s0)));
                q.setImproveScore(MutantSelector.score(r0));
                out.put("q1Mutants", chosen.stream().map(m -> m.id).collect(Collectors.joining(";")));
            }
            if (Config.IMPROVE_CONTEXT_FCE.equals(config.getImproveFocalContext())) {
                q.setImproveFce(classInfo.getCompilationUnitCode());
            } else {
                q.setPrivateBodies(privateBodies(s0));
            }
            PromptConstructorImpl qpc = new PromptConstructorImpl(config);
            qpc.setPromptInfo(q);
            qpc.setFullTestName(qName);
            Outcome qo = generateAndRepair(phase, qpc, true, config.getMaxRepairPerCycle());
            putImprovementStats(q, qo);
            exportRecord(s0, q.getRecords(), classInfo, num, "records_Q1.json");
            if (qo == Outcome.FAILED) {
                Files.deleteIfExists(qPath);
                out.put("q1Outcome", "DISCARDED_RED");
                return;
            }

            // 4. PIT on A_1 = S0 + Q_1; a failing green run can only prune tests of Q_1
            String qCode = new String(Files.readAllBytes(qPath), StandardCharsets.UTF_8);
            tests = new LinkedHashMap<>();
            tests.put(s0Name, s0Code);
            tests.put(qName, qCode);
            MutationResult r1 = pit(s0, tests, Collections.singleton(qName));
            putResult("a1", r1);
            out.put("q1GreenPrunedTests", String.valueOf(prunedCount(r1, qName)));
            if (r1.getStatus() != MutationResult.Status.OK || r1.getFinalTests() == null
                    || !r1.getFinalTests().containsKey(qName)) {
                Files.deleteIfExists(qPath);
                out.put("q1Outcome", "DISCARDED_PIT_" + r1.getStatus());
                return;
            }
            if (prunedCount(r1, qName) > 0) {
                exportTest(r1.getFinalTests().get(qName), qPath);
            }
            Set<String> chosenIds = chosen.stream().map(m -> m.id).collect(Collectors.toSet());
            long killedChosen = r1.getMutants().stream().filter(m -> m.detected && chosenIds.contains(m.id)).count();
            out.put("q1KilledChosen", String.valueOf(killedChosen));
            out.put("q1Outcome", qo == Outcome.PARTIAL ? "VALID_PARTIAL"
                    : "PASSED_AFTER_PRUNE".equals(q.getValidationOutcome()) ? "VALID_PRUNED" : "VALID_PASSED");
        }

        MutationResult pit(PromptInfo s0, Map<String, String> tests, Set<String> prunable) {
            MutationRequest req = MutationRequest.from(config, s0, tests);
            req.setMutators(config.getMutators());
            req.setProcessTimeoutSeconds(config.getPitTimeout());
            MutationResult r = analyzer.analyzeWithGreenPruning(req, MAX_GREEN_PRUNES, prunable);
            pitMillis += r.getTotalMillis();
            config.getLogger().info("PIT on " + tests.keySet() + ": " + r.getStatus() + ", " + r.getDetected() + "/"
                    + r.getTotal() + " detected, " + r.getSurvived() + " survived (" + r.getTotalMillis() + " ms)");
            if (r.getStatus() != MutationResult.Status.OK) {
                config.getLogger().warn("PIT log tail:\n" + r.getLogTail() + "\n" + String.join("\n", r.getCompileErrors()));
            }
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

        void putImprovementStats(PromptInfo q, Outcome qo) {
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
            out.put("q1Validation", qo + "/" + q.getValidationOutcome());
            out.put("q1Rounds", String.valueOf(q.getRecords().size() - 1));
            out.put("q1InRoundRestarts", String.valueOf(q.getInRoundRestarts()));
            out.put("q1StagnantRounds", String.valueOf(q.getStagnantRounds()));
            out.put("q1TestsExported", String.valueOf(q.getTestsExported()));
            out.put("q1TestsPruned", String.valueOf(q.getTestsPruned()));
            out.put("q1TruncatedResponses", String.valueOf(q.countTruncatedResponses(config.getMaxResponseTokens())));
            out.put("improveInputTokens", String.valueOf(improveIn));
            out.put("improveOutputTokens", String.valueOf(improveOut));
            out.put("improveRepairInputTokens", String.valueOf(repairIn));
            out.put("improveRepairOutputTokens", String.valueOf(repairOut));
        }

        int prunedCount(MutationResult r, String testClass) {
            Set<String> pruned = r.getPrunedTests() == null ? null : r.getPrunedTests().get(testClass);
            return pruned == null ? 0 : pruned.size();
        }
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