package zju.cst.aces.runner;

import zju.cst.aces.api.phase.Phase;
import zju.cst.aces.api.phase.PhaseImpl;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.impl.PromptConstructorImpl;
import zju.cst.aces.api.impl.obfuscator.Obfuscator;
import zju.cst.aces.dto.*;
import zju.cst.aces.runner.ClassRunner;
import zju.cst.aces.util.JsonResponseProcessor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

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

        config.getLogger().info("SOFIA ACTIVATIONS NUMBER: " + promptInfo.sofiaActivations);
        if (promptInfo.sofiaActivations == 0) {
            return false;
        }

        promptInfo.setRound(0);

        long startTime = System.nanoTime();
        // Test Generation Phase
        phase.generateTest(pc);


        // Validation
        if (phase.validateTest(pc)) {
            long endTime = System.nanoTime();
            float duration = (float)(endTime - startTime)/ 1_000_000_000;
            exportRecord(pc.getPromptInfo(), classInfo, num, duration, true);

            return true;
        }

        // Validation and Repair Phase
        for (int rounds = 1; rounds < config.getMaxRounds(); rounds++) {

            promptInfo.setRound(rounds);

            // Repair
            phase.repairTest(pc);


            // Validation and process
            if (phase.validateTest(pc)) { // if passed validation
                long endTime = System.nanoTime();
                float duration = (float)(endTime - startTime)/ 1_000_000_000;
                exportRecord(pc.getPromptInfo(), classInfo, num, duration, true);
                return true;
            }

        }

        long endTime = System.nanoTime();
        float duration = (float)(endTime - startTime)/ 1_000_000_000;
        exportRecord(pc.getPromptInfo(), classInfo, num, duration, false);
        return false;
    }
}