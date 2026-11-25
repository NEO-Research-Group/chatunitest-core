package zju.cst.aces.dto;

import com.google.gson.annotations.SerializedName;
import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ChatMessage {
    private String role;
    private String content;
    // Reasoning of the response, if the server returns it apart (Ollama: "reasoning"; DeepSeek: "reasoning_content").
    // Null in the requests, so it is not sent (Gson skips nulls)
    @SerializedName(value = "reasoning", alternate = {"reasoning_content"})
    private String reasoning;

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public static ChatMessage of(String content) {

        return new ChatMessage(ChatMessage.Role.USER.getValue(), content);
    }

    public static ChatMessage ofSystem(String content) {

        return new ChatMessage(Role.SYSTEM.getValue(), content);
    }

    public static ChatMessage ofAssistant(String content) {

        return new ChatMessage(Role.ASSISTANT.getValue(), content);
    }

    @Getter
    @AllArgsConstructor
    public enum Role {

        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant"),
        ;
        private final String value;
    }

}