package zju.cst.aces.dto;

import lombok.Data;

import java.util.List;

@Data
public class RoundRecord {
    public int attempt;
    public int round;
    public List<ChatMessage> prompt;
    public String response;
    public int promptToken;
    public int responseToken;
    public boolean hasCode;
    public String code;
    public boolean hasError;
    public TestMessage errorMsg;
    public boolean stagnant; // the repair prompt was identical to the previous round's (Config.stagnationStrategy)
    public String promptHash; // MD5 of the prompt as generated, before any change made by the stagnation strategy
    // Improvement repair (phase 2): the response returned the input test unchanged, and the round was asked again
    // with the improvement prompt. The tokens of the discarded call are kept here and counted in the totals.
    public boolean inRoundRestart;
    public int discardedPromptToken;
    public int discardedResponseToken;
    // Reasoning tokens of the response, if the provider reports them apart (0 otherwise, e.g. Codestral)
    public int reasoningToken;

    public RoundRecord(int round) {
        this.round = round;
    }
}
