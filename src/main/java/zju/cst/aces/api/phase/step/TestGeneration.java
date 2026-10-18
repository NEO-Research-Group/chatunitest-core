package zju.cst.aces.api.phase.step;

import lombok.Data;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.impl.ChatGenerator;
import zju.cst.aces.api.impl.PromptConstructorImpl;
import zju.cst.aces.api.impl.RepairImpl;
import zju.cst.aces.api.impl.obfuscator.Obfuscator;
import zju.cst.aces.dto.*;
import zju.cst.aces.prompt.PromptGenerator;
import zju.cst.aces.runner.MethodRunner;
import zju.cst.aces.util.CodeExtractor;

import java.util.List;

public class TestGeneration {
    protected final Config config;
    protected PromptGenerator promptGenerator;
    protected MethodInfo methodInfo;

    public TestGeneration(Config config) {
        this.config = config;
    }

    public void setUp(PromptInfo promptInfo) {
        this.promptGenerator = new PromptGenerator(config);
        this.methodInfo = promptInfo.getMethodInfo();
    }

    public void execute(PromptConstructorImpl pc) {

        PromptInfo promptInfo = pc.getPromptInfo();
        if (promptGenerator == null) {
            setUp(promptInfo);
        }

        assert (promptInfo.getRound() != null);

        int rounds = promptInfo.getRound();
        promptInfo.addRecord(new RoundRecord(rounds));
        RoundRecord record = promptInfo.getRecords().get(rounds);
        record.setAttempt(promptInfo.getTestNum());

        if (rounds == 0) {
            config.getLogger().info("Generating test for method < " + methodInfo.methodName + " > round " + rounds + " ...");
        } else {
            config.getLogger().info("Fixing test for method < " + methodInfo.methodName + " > round " + rounds + " ...");
        }

        List<ChatMessage> prompt;
        String code;
        if (config.isEnableObfuscate()) {
            Obfuscator obfuscator = new Obfuscator(config);
            PromptInfo obfuscatedPromptInfo = new PromptInfo(promptInfo);
            obfuscator.obfuscatePromptInfo(obfuscatedPromptInfo);
            prompt = promptGenerator.generateMessages(obfuscatedPromptInfo,config.getPhaseType());
            code = generateTest(prompt, record);
            if (!record.isHasCode()) {
                promptInfo.setUnitTest("");
                return;
            }
            code = obfuscator.deobfuscateJava(code);
        } else {
            prompt = promptGenerator.generateMessages(promptInfo,config.getPhaseType());
            if (isStagnant(promptInfo, prompt, rounds)) {
                record.setStagnant(true);
                promptInfo.setStagnantRounds(promptInfo.getStagnantRounds() + 1);
                config.getLogger().info("Repair prompt for method < " + methodInfo.methodName + " > identical to round "
                        + (rounds - 1) + ": stagnationStrategy=" + config.getStagnationStrategy());
                if (Config.STAGNATION_RESTART.equals(config.getStagnationStrategy())) {
                    promptInfo.setErrorMsg(null); // the prompt generator falls back to the initial prompt
                    prompt = promptGenerator.generateMessages(promptInfo, config.getPhaseType());
                }
            }
            code = generateTest(prompt, record);
            if (!record.isHasCode()) {
                promptInfo.setUnitTest("");
                return;
            }
        }
        RepairImpl repair = new RepairImpl(config, pc);
        if (CodeExtractor.isTestMethod(code)) {
            TestSkeleton skeleton = new TestSkeleton(promptInfo); // test skeleton to wrap a test method
            code = skeleton.build(code);
        } else {
            code = repair.ruleBasedRepair(code);
        }
        promptInfo.setUnitTest(code);
        record.setCode(code);
    }

    /** A repair round is stagnant when its prompt is the same as the previous round's (same code, same error). */
    protected boolean isStagnant(PromptInfo promptInfo, List<ChatMessage> prompt, int rounds) {
        if (rounds < 1 || promptInfo.getRecords().size() < rounds) {
            return false;
        }
        List<ChatMessage> previous = promptInfo.getRecords().get(rounds - 1).getPrompt();
        return previous != null && previous.equals(prompt);
    }

    public String generateTest(List<ChatMessage> prompt, RoundRecord record) {

        if (MethodRunner.isExceedMaxTokens(config.getMaxPromptTokens(), prompt)) {
            config.getLogger().error("Exceed max prompt tokens: " + methodInfo.methodName + " Skipped.");
            record.setPromptToken(-1);
            record.setHasCode(false);
            return "";
        }
        config.getLogger().debug("[Prompt]:\n" + prompt);

        ChatResponse response;
        if (record.isStagnant() && !Config.STAGNATION_NONE.equals(config.getStagnationStrategy())) {
            response = ChatGenerator.chat(config, prompt, config.getStagnationTemperature(), true);
        } else {
            response = ChatGenerator.chat(config, prompt);
        }
        String content = ChatGenerator.getContentByResponse(response);
        config.getLogger().debug("[Response]:\n" + content);
        String code = ChatGenerator.extractCodeByContent(content);

        record.setPromptToken(response.getUsage().getPromptTokens());
        record.setResponseToken(response.getUsage().getCompletionTokens());
        record.setPrompt(prompt);
        record.setResponse(content);
        if (code.isEmpty()) {
            config.getLogger().info("Test for method < " + methodInfo.methodName + " > extract code failed");
            record.setHasCode(false);
            return "";
        }
        record.setHasCode(true);
        return code;
    }

}
