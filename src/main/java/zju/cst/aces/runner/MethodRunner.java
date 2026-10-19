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
import zju.cst.aces.util.TestProcessor;

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
        List<Map<String, Object>> arms = new ArrayList<>();
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
            for (Map<String, Object> arm : arms) {
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

    public enum Outcome { PASSED, PARTIAL, FAILED, DUPLICATE }

    /**
     * Round 0 (generation, or improvement if improve) + validation, and repair + validation up to lastRound.
     * If every round fails, the tests that passed in some round are validated on their own (PARTIAL_CORRECT_TESTS).
     * Used for S0 and for each improvement class Q_i, so that both are repaired in the same way. In Q_i, the tests
     * that repeat a test of A_{i-1} are removed before validating round 0; if none is left, DUPLICATE (no repair).
     */
    public Outcome generateAndRepair(Phase phase, PromptConstructorImpl pc, boolean improve, int lastRound) {
        PromptInfo promptInfo = pc.getPromptInfo();
        promptInfo.setRound(0);
        if (improve) {
            phase.improveTest(pc);
            if (!promptInfo.getUnitTest().isEmpty()) {
                String dedup = removeDuplicates(promptInfo, pc.getFullTestName(), promptInfo.getUnitTest());
                if (dedup == null) {
                    return Outcome.DUPLICATE;
                }
                promptInfo.setUnitTest(dedup);
            }
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
     * Removes from code the tests whose normalized body (TestProcessor.testBodies, as norm_body of
     * scripts/eval_cycles.py) is that of a test of A_{i-1} (promptInfo.duplicateBodies), and counts them in
     * promptInfo.testsDuplicated. Returns code if nothing is removed (or it cannot be parsed), null if no test is left.
     */
    String removeDuplicates(PromptInfo promptInfo, String testName, String code) {
        if (promptInfo.getDuplicateBodies() == null) {
            return code;
        }
        TestProcessor tp = new TestProcessor(testName);
        Map<String, String> bodies = tp.testBodies(code);
        if (bodies == null) {
            return code;
        }
        Set<String> dup = bodies.entrySet().stream().filter(e -> promptInfo.getDuplicateBodies().contains(e.getValue()))
                .map(Map.Entry::getKey).collect(Collectors.toCollection(LinkedHashSet::new));
        if (dup.isEmpty()) {
            return code;
        }
        promptInfo.setTestsDuplicated(promptInfo.getTestsDuplicated() + dup.size());
        config.getLogger().info("Removed " + dup.size() + " tests of < " + testName + " > that repeat a test of the suite: " + dup);
        return dup.size() == bodies.size() ? null : tp.removeTestsByName(code, dup);
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
        final Map<String, Object> out = new LinkedHashMap<>();
        final List<Map<String, Object>> cycles = new ArrayList<>();
        final MutantSelector selector = new MutantSelector(config.getMaxMutantsPerPrompt(), config.getMaxMutantAttempts(),
                config.getExcludedMutantIds());
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

        Map<String, Object> run() {
            long t0 = System.nanoTime();
            out.put("qualityEnabled", "true");
            out.put("improvePromptType", arm);
            out.put("s0Shared", String.valueOf(Config.IMPROVE_BOTH.equals(config.getImprovePromptType())));
            out.put("improveFocalContext", config.getImproveFocalContext());
            out.put("mutators", config.getMutators());
            out.put("maxRounds", String.valueOf(config.getMaxRounds()));
            out.put("maxQualityCycles", String.valueOf(config.getMaxQualityCycles()));
            out.put("maxRepairPerCycle", String.valueOf(config.getMaxRepairPerCycle()));
            out.put("maxMutantsPerPrompt", String.valueOf(config.getMaxMutantsPerPrompt()));
            out.put("maxMutantAttempts", String.valueOf(config.getMaxMutantAttempts()));
            out.put("stagnationCycles", String.valueOf(config.getStagnationCycles()));
            out.put("excludedMutants", String.valueOf(config.getExcludedMutantIds().size()));
            putS0();
            if (s0.error != null) {
                stop("ERROR", 0);
                out.put("qualityError", s0.error);
            } else {
                try {
                    cycles();
                } catch (Exception e) {
                    config.getLogger().warn("Quality cycles of < " + methodInfo.methodName + " > (" + arm + ") failed: " + e);
                    stop("ERROR", current);
                    out.put("qualityError", e.toString());
                }
            }
            long valid = cycles.stream().filter(c -> "true".equals(c.get("valid"))).count();
            out.put("validCycles", String.valueOf(valid));
            out.put("discardedCycles", String.valueOf(cycles.size() - valid));
            out.put("pitTimeMillis", String.valueOf(pitMillis + (s0.result == null ? 0 : s0.result.getTotalMillis())));
            out.put("reasoningTokens", String.valueOf(reasoningTokens(s0.info.getRecords())
                    + cycles.stream().mapToInt(c -> Integer.parseInt((String) c.get("reasoningTokens"))).sum()));
            out.put("qualityTime", String.valueOf((float) (System.nanoTime() - t0) / 1_000_000_000));
            out.put("cycles", cycles);
            return out;
        }

        /** S0 as it enters the cycles (after the green-run pruning of PIT); the same in both arms. */
        void putS0() {
            out.put("roundS0", String.valueOf(s0.info.getRound()));
            out.put("s0_reasoningTokens", String.valueOf(reasoningTokens(s0.info.getRecords())));
            if (s0.code != null) {
                TestProcessor tp = new TestProcessor(s0.name);
                out.put("s0_tests", String.valueOf(tp.countTestCases(s0.code)));
                out.put("s0_asserts", String.valueOf(tp.countAssertions(s0.code)));
            }
            if (s0.result != null) {
                out.put("s0_pitStatus", String.valueOf(s0.result.getStatus()));
                out.put("s0_pitPruned", String.valueOf(s0.pitPruned));
                out.put("s0_pitTime", String.valueOf(s0.result.getTotalMillis()));
                Map<String, String> m = new LinkedHashMap<>();
                putMutation(m, s0.result);
                putMutationNoExcluded(m, s0.result);
                m.forEach((k, v) -> out.put("s0_" + k, v));
            }
        }

        /**
         * Stops at the first of (enfoque.md §3.3): N cycles done (MAX_CYCLES); no target left, that is, no SURVIVED
         * or NO_COVERAGE mutant that is not excluded (NO_TARGETS, NO_SURVIVED before E2); only targets tried
         * maxMutantAttempts times (ALL_EXHAUSTED); stagnationCycles cycles in a row without killing any new mutant
         * (STAGNATION). The generic arm selects and counts the attempts in the same way, without showing the
         * targets, so that both arms stop by the same rule.
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
                if (!selector.hasTargets(prev)) {
                    stop("NO_TARGETS", i - 1);
                    return;
                }
                if (selector.candidates(prev).isEmpty()) {
                    stop("ALL_EXHAUSTED", i - 1);
                    return;
                }
                long t0 = System.nanoTime();
                Map<String, Object> c = new LinkedHashMap<>();
                cycles.add(c);
                MutationResult next = cycle(i, selector.select(prev), prev, c);
                Set<String> killed = detectedIds(next);
                killed.removeAll(detectedIds(prev));
                putSuite(c, next);
                c.put("newKilled", String.valueOf(killed.size()));
                c.put("time", String.valueOf((float) (System.nanoTime() - t0) / 1_000_000_000));
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

        /** Cycle i from A_{i-1} (PIT result prev), recorded in c; returns the PIT result of A_i. */
        MutationResult cycle(int i, List<MutantSelector.Target> targets, MutationResult prev, Map<String, Object> c)
                throws IOException {
            List<Mutant> chosen = targets.stream().flatMap(t -> t.mutants.stream()).collect(Collectors.toList());
            String suiteCode = String.join("\n\n", suite.values());
            if (Config.IMPROVE_MUTANTS.equals(arm)) {
                MutantSelector.setHints(targets, suiteCode);
            }
            List<Map<String, Object>> targetMaps = targets.stream().map(MutantSelector.Target::toMap)
                    .collect(Collectors.toList());
            String qName = s0.name.substring(0, s0.name.length() - "_Test".length()) + "_" + tag + i + "_Test";
            Path qPath = config.getTestOutput().resolve(qName.replace(".", File.separator) + ".java");
            c.put("cycle", String.valueOf(i));
            c.put("testClass", qName);
            PromptInfo q = PromptInfo.forImprovement(s0.info, qName, qPath);
            q.setImproveMode(arm);
            q.setSuiteTests(suiteCode);
            q.setPruneFromRound(config.getMaxRepairPerCycle());
            Set<String> suiteBodies = new HashSet<>();
            for (Map.Entry<String, String> e : suite.entrySet()) {
                Map<String, String> b = new TestProcessor(e.getKey()).testBodies(e.getValue());
                if (b != null) {
                    suiteBodies.addAll(b.values());
                }
            }
            q.setDuplicateBodies(suiteBodies);
            // A list: the ids contain the JVM descriptor of the method, with ';'
            List<String> chosenIds = chosen.stream().map(m -> m.id).collect(Collectors.toList());
            if (Config.IMPROVE_MUTANTS.equals(arm)) {
                List<String> lines = focalSourceLines(s0.info);
                q.setImproveMutants(MutantSelector.formatSurvived(targets, lines));
                q.setImproveMutantsNc(MutantSelector.formatBlocks(targets, lines));
                q.setImproveScore(MutantSelector.score(prev, config.getExcludedMutantIds()));
                c.put("shownTargets", targetMaps);
                c.put("shownMutants", chosenIds);
            } else {
                c.put("hiddenTargets", targetMaps);  // chosen and counted as attempts, not in the prompt
                c.put("hiddenMutants", chosenIds);
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
            putImprovementStats(c, q, qo);
            exportRecord(s0.info, q.getRecords(), classInfo, num, "records_" + tag + i + ".json");
            if (qo == Outcome.FAILED || qo == Outcome.DUPLICATE) {
                c.put("qTestsDuplicated", String.valueOf(q.getTestsDuplicated()));
                Files.deleteIfExists(qPath);
                discard(c, qo == Outcome.FAILED ? "DISCARDED_RED" : "DISCARDED_DUPLICATE");
                return prev;
            }
            // The repair may bring back a test of A_{i-1}: remove the repeated tests again before PIT
            String qGreen = new String(Files.readAllBytes(qPath), StandardCharsets.UTF_8);
            String qDedup = removeDuplicates(q, qName, qGreen);
            c.put("qTestsDuplicated", String.valueOf(q.getTestsDuplicated()));
            if (qDedup == null) {
                Files.deleteIfExists(qPath);
                discard(c, "DISCARDED_DUPLICATE");
                return prev;
            }
            if (!qDedup.equals(qGreen)) {
                exportTest(qDedup, qPath);
            }

            // PIT on A_i = A_{i-1} + Q_i; a failing green run can only prune tests of Q_i
            Map<String, String> tests = new LinkedHashMap<>(suite);
            tests.put(qName, qDedup);
            MutationResult r = pit(s0.info, tests, Collections.singleton(qName));
            pitMillis += r.getTotalMillis();
            c.put("pitStatus", String.valueOf(r.getStatus()));
            c.put("pitTime", String.valueOf(r.getTotalMillis()));
            c.put("pitPruned", String.valueOf(prunedCount(r, qName)));
            if (r.getStatus() != MutationResult.Status.OK || r.getFinalTests() == null
                    || !r.getFinalTests().containsKey(qName)) {
                Files.deleteIfExists(qPath);
                discard(c, "DISCARDED_PIT_" + r.getStatus());
                return prev;
            }
            String qCode = r.getFinalTests().get(qName);
            if (prunedCount(r, qName) > 0) {
                exportTest(qCode, qPath);
            }
            suite.put(qName, qCode);
            TestProcessor tp = new TestProcessor(qName);
            c.put("valid", "true");
            c.put("outcome", qo == Outcome.PARTIAL ? "VALID_PARTIAL"
                    : "PASSED_AFTER_PRUNE".equals(q.getValidationOutcome()) ? "VALID_PRUNED" : "VALID_PASSED");
            c.put("qTests", String.valueOf(tp.countTestCases(qCode)));
            c.put("qAsserts", String.valueOf(tp.countAssertions(qCode)));
            Set<String> chosenSet = chosen.stream().map(m -> m.id).collect(Collectors.toSet());
            Set<String> chosenNc = targets.stream().filter(t -> MutantSelector.NC_BLOCK.equals(t.type))
                    .flatMap(t -> t.mutants.stream()).map(m -> m.id).collect(Collectors.toSet());
            c.put("killedChosen", String.valueOf(r.getMutants().stream()
                    .filter(m -> m.detected && chosenSet.contains(m.id)).count()));
            c.put("killedChosenNc", String.valueOf(r.getMutants().stream()
                    .filter(m -> m.detected && chosenNc.contains(m.id)).count()));
            return r;
        }

        void discard(Map<String, Object> c, String outcome) {
            c.put("valid", "false");
            c.put("outcome", outcome);
            c.putIfAbsent("pitStatus", "NOT_RUN");   // red: A_i = A_{i-1}, no PIT (same keys in every cycle)
            c.putIfAbsent("pitTime", "0");
            c.putIfAbsent("pitPruned", "0");
            c.put("qTests", "0");
            c.put("qAsserts", "0");
            c.put("killedChosen", "0");
            c.put("killedChosenNc", "0");
        }

        /** The accumulated suite A_i and its PIT result (A_{i-1} if the cycle was discarded). */
        void putSuite(Map<String, Object> c, MutationResult r) {
            int tests = 0, asserts = 0;
            for (Map.Entry<String, String> e : suite.entrySet()) {
                TestProcessor tp = new TestProcessor(e.getKey());
                tests += tp.countTestCases(e.getValue());
                asserts += tp.countAssertions(e.getValue());
            }
            c.put("suiteClasses", String.valueOf(suite.size()));
            c.put("tests", String.valueOf(tests));
            c.put("asserts", String.valueOf(asserts));
            putMutation(c, r);
            putMutationNoExcluded(c, r);
        }

        void putImprovementStats(Map<String, Object> c, PromptInfo q, Outcome qo) {
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
            c.put("generation", String.valueOf(qo));
            c.put("validationOutcome", q.getValidationOutcome());
            c.put("greenFromRound", String.valueOf(q.getGreenFromRound()));
            c.put("repairRounds", String.valueOf(q.getRecords().size() - 1));
            c.put("restarts", String.valueOf(q.getInRoundRestarts()));
            c.put("stagnantRounds", String.valueOf(q.getStagnantRounds()));
            c.put("truncatedResponses", String.valueOf(q.countTruncatedResponses(config.getMaxResponseTokens())));
            c.put("qTestsExported", String.valueOf(q.getTestsExported()));
            c.put("qTestsPruned", String.valueOf(q.getTestsPruned()));
            c.put("improveInputTokens", String.valueOf(improveIn));
            c.put("improveOutputTokens", String.valueOf(improveOut));
            c.put("repairInputTokens", String.valueOf(repairIn));
            c.put("repairOutputTokens", String.valueOf(repairOut));
            c.put("reasoningTokens", String.valueOf(reasoningTokens(q.getRecords())));
        }
    }

    /** Mutation counts of the focal method; killed = detected (KILLED, TIMED_OUT, MEMORY_ERROR, RUN_ERROR...). */
    static void putMutation(Map<String, ? super String> m, MutationResult r) {
        m.put("mutants", String.valueOf(r.getTotal()));
        m.put("killed", String.valueOf(r.getDetected()));
        m.put("survived", String.valueOf(r.getSurvived()));
        m.put("noCoverage", String.valueOf(r.getNoCoverage()));
        m.put("ms", String.valueOf(r.getMs()));
        m.put("ts", String.valueOf(r.getTs()));
    }

    /** The same counts without the excluded mutants (excludedMutants), with the suffix NoEq; nothing if none. */
    void putMutationNoExcluded(Map<String, ? super String> m, MutationResult r) {
        if (!config.getExcludedMutantIds().isEmpty()) {
            MutantSelector.countsWithout(r, config.getExcludedMutantIds()).forEach((k, v) -> m.put(k + "NoEq", v));
        }
    }

    static int reasoningTokens(List<RoundRecord> records) {
        return records.stream().mapToInt(RoundRecord::getReasoningToken).sum();
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