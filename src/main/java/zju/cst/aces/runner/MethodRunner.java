package zju.cst.aces.runner;

import zju.cst.aces.api.phase.Phase;
import zju.cst.aces.api.phase.PhaseImpl;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.impl.PromptConstructorImpl;
import zju.cst.aces.coverage.CodeCoverageAnalyzerSofia;
import zju.cst.aces.dto.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

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
        if (promptInfo.sofiaActivations == 0 || conditionsCount == 0) {
            return false;
        }

        promptInfo.setRound(0);

        long startTime = System.nanoTime();
        // Test Generation Phase
        phase.generateTest(pc);


        // Validation
        if (phase.validateTest(pc)) {
            exportRecord(pc.getPromptInfo(), classInfo, num);
            if (config.generateJsonReport) {
                long endTime = System.nanoTime();
                float duration = (float)(endTime - startTime)/ 1_000_000_000;
                String branchCoveragePercentage = getCoverage(classInfo, methodInfo, pc.getPromptInfo());
                generateJsonReport(pc.getPromptInfo(), duration, true, branchCoveragePercentage);
            }

            return true;
        }

        // Validation and Repair Phase
        for (int rounds = 1; rounds < config.getMaxRounds(); rounds++) {

            promptInfo.setRound(rounds);

            // Repair
            phase.repairTest(pc);


            // Validation and process
            if (phase.validateTest(pc)) { // if passed validation
                exportRecord(pc.getPromptInfo(), classInfo, num);
                if (config.generateJsonReport) {
                    long endTime = System.nanoTime();
                    float duration = (float) (endTime - startTime) / 1_000_000_000;
                    String branchCoveragePercentage = getCoverage(classInfo, methodInfo, pc.getPromptInfo());
                    generateJsonReport(pc.getPromptInfo(), duration, true, branchCoveragePercentage);
                }
                return true;
            }

        }

        exportRecord(pc.getPromptInfo(), classInfo, num);
        if (config.generateJsonReport) {
            long endTime = System.nanoTime();
            float duration = (float) (endTime - startTime) / 1_000_000_000;
            generateJsonReport(pc.getPromptInfo(), duration, false, "0.0");
        }
        return false;
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
            });
        }
        return count.get();
    }

    public String getCoverage(ClassInfo classInfo, MethodInfo methodInfo, PromptInfo promptInfo) {
        Map<String, Object> coverageInfo = new HashMap<>();

        try {
            coverageInfo = new CodeCoverageAnalyzerSofia().analyzeCoverage(
                    promptInfo.getUnitTest(), promptInfo.getFullTestName(),
                    classInfo.getFullClassName(),
                    methodInfo.getMethodSignature(),
                    config.project.getBuildPath().toString(),
                    config.project.getCompileSourceRoots().get(0),
                    config.classPaths
            );
        } catch (Exception e) {
            config.getLogger().info("Error during code coverage analysis: " + e.getMessage());
            return "Error during code coverage analysis";
        }

        return String.valueOf(coverageInfo.get("branchCoverage"));
    }
}