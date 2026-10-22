package zju.cst.aces.dto;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

@Data
public class ChatUsage {
    @SerializedName("prompt_tokens")
    private Integer promptTokens;
    @SerializedName("completion_tokens")
    private Integer completionTokens;
    @SerializedName("total_tokens")
    private Integer totalTokens;
    @SerializedName("completion_tokens_details")
    private CompletionTokensDetails completionTokensDetails;

    public int getReasoningTokens() {
        return completionTokensDetails == null || completionTokensDetails.reasoningTokens == null
                ? 0 : completionTokensDetails.reasoningTokens;
    }

    @Data
    public static class CompletionTokensDetails {
        @SerializedName("reasoning_tokens")
        private Integer reasoningTokens;
    }
}
