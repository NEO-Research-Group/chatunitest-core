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
    protected boolean improveCall;   // round 0 of an improvement class Q_i: Config.improveTemperature applies

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
            record.setPromptHash(hash(prompt));
            if (isStagnant(promptInfo, record, rounds)) {
                record.setStagnant(true);
                promptInfo.setStagnantRounds(promptInfo.getStagnantRounds() + 1);
                config.getLogger().info("Repair prompt for method < " + methodInfo.methodName + " > identical to round "
                        + (rounds - 1) + ": stagnationStrategy=" + config.getStagnationStrategy());
                if (Config.STAGNATION_RESTART.equals(config.getStagnationStrategy())) {
                    promptInfo.setErrorMsg(null); // the prompt generator falls back to the initial prompt
                    prompt = promptGenerator.generateMessages(promptInfo, config.getPhaseType());
                } else if (Config.STAGNATION_FEEDBACK.equals(config.getStagnationStrategy())) {
                    prompt = withStagnationNote(prompt);
                }
            }
            improveCall = rounds == 0 && promptInfo.getImproveMode() != null;
            code = generateTest(prompt, record);
            improveCall = false;
            if (rounds >= 1 && promptInfo.getImproveMode() != null && record.isHasCode()
                    && sameCode(code, promptInfo.getUnitTest())) {
                // Improvement repair: the response returns the input test unchanged. Ask again in the same round
                // with the improvement prompt (same mutants), as stagnationStrategy=restart, without spending a round.
                config.getLogger().info("Repair of < " + pc.getTestName() + " > returned the same test: asking again with the improvement prompt");
                record.setInRoundRestart(true);
                record.setStagnant(true);
                record.setDiscardedPromptToken(record.getPromptToken());
                record.setDiscardedResponseToken(record.getResponseToken());
                promptInfo.setInRoundRestarts(promptInfo.getInRoundRestarts() + 1);
                promptInfo.setErrorMsg(null);
                prompt = promptGenerator.generateMessages(promptInfo, config.getPhaseType());
                code = generateTest(prompt, record);
            }
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

    public static final String STAGNATION_NOTE = "\n\nNote: this same unit test was already returned in the previous attempt"
            + " and it still fails with the same error. Do not return it unchanged: rewrite the lines that cause the error,"
            + " or remove that part of the test, so that the error disappears.";

    /**
     * A repair round is stagnant when its prompt, as generated, is the same as the previous round's (same code, same
     * error). The hashes are compared, so a prompt changed by the stagnation strategy does not hide the next repetition.
     */
    protected boolean isStagnant(PromptInfo promptInfo, RoundRecord record, int rounds) {
        if (rounds < 1 || promptInfo.getRecords().size() < rounds) {
            return false;
        }
        String previous = promptInfo.getRecords().get(rounds - 1).getPromptHash();
        return previous != null && previous.equals(record.getPromptHash());
    }

    /** Same Java code, ignoring comments and layout (falls back to comparing without whitespace if it does not parse). */
    protected static boolean sameCode(String a, String b) {
        if (a == null || b == null || b.isEmpty()) {
            return false;
        }
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String code) {
        try {
            com.github.javaparser.ast.CompilationUnit cu = new com.github.javaparser.JavaParser().parse(code).getResult()
                    .orElseThrow(java.util.NoSuchElementException::new);
            cu.getAllContainedComments().forEach(com.github.javaparser.ast.comments.Comment::remove);
            code = cu.toString();
        } catch (Exception e) {
            // keep the raw code
        }
        return code.replaceAll("\\s+", "");
    }

    /** Copy of the prompt with STAGNATION_NOTE appended to the last message. */
    protected static List<ChatMessage> withStagnationNote(List<ChatMessage> prompt) {
        List<ChatMessage> changed = new java.util.ArrayList<>();
        for (ChatMessage m : prompt) {
            changed.add(new ChatMessage(m.getRole(), m.getContent()));
        }
        ChatMessage last = changed.get(changed.size() - 1);
        last.setContent(last.getContent() + STAGNATION_NOTE);
        return changed;
    }

    protected static String hash(List<ChatMessage> prompt) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : prompt) {
            sb.append(m.getRole()).append('\u0000').append(m.getContent()).append('\u0001');
        }
        try {
            byte[] d = java.security.MessageDigest.getInstance("MD5").digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return String.valueOf(sb.toString().hashCode());
        }
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
        if (record.isStagnant() && (Config.STAGNATION_TEMPERATURE.equals(config.getStagnationStrategy())
                || Config.STAGNATION_RESTART.equals(config.getStagnationStrategy()))) {
            response = ChatGenerator.chat(config, prompt, config.getStagnationTemperature(), true);
        } else if (improveCall && config.getImproveTemperature() >= 0) {
            // Variety between cycles (E2): the same improvement prompt at T = 0 returns the same class
            response = ChatGenerator.chat(config, prompt, config.getImproveTemperature(), true);
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
