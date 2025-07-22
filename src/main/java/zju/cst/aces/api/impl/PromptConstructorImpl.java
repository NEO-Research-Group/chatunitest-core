package zju.cst.aces.api.impl;

import lombok.Data;
import zju.cst.aces.api.PromptConstructor;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.dto.ChatMessage;
import zju.cst.aces.dto.ClassInfo;
import zju.cst.aces.dto.MethodInfo;
import zju.cst.aces.dto.PromptInfo;
import zju.cst.aces.prompt.PromptGenerator;
import zju.cst.aces.runner.AbstractRunner;
import zju.cst.aces.runner.solution_runner.ECRRunner;
import zju.cst.aces.runner.solution_runner.ExpRunner;
import zju.cst.aces.runner.solution_runner.SofiaHitsRunner;
import zju.cst.aces.runner.solution_runner.SofiaRunner;
import zju.cst.aces.util.TokenCounter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Data
public class PromptConstructorImpl implements PromptConstructor {

    Config config;
    PromptInfo promptInfo;
    List<ChatMessage> chatMessages;
    int tokenCount = 0;
    String testName;
    String fullTestName;
    static final String separator = "_";

    public PromptConstructorImpl(Config config) {
        this.config = config;
    }

    @Override
    public List<ChatMessage> generate() {
        try {
            if (promptInfo == null) {
                throw new RuntimeException("PromptInfo is null, you need to initialize it first.");
            }
            this.chatMessages = new PromptGenerator(config).generateMessages(promptInfo,config.phaseType);
            countToken();
            return this.chatMessages;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void setPromptInfoWithDep(ClassInfo classInfo, MethodInfo methodInfo) throws IOException {
        if (config.getPhaseType().equals("SOFIA") || config.getPhaseType().equals("SOFIA_OLD") ||
                config.getPhaseType().equals("FCE")) {
            SofiaRunner.setStaticParams(config);
            this.promptInfo = SofiaRunner.generatePromptInfoWithDep(config, classInfo, methodInfo);
        } else if (config.getPhaseType().equals("ECR")) {
            ECRRunner.setStaticParams(config);
            this.promptInfo = ECRRunner.generatePromptInfoWithDep(config, classInfo, methodInfo);
        } else if (config.getPhaseType().equals("SOFIA_HITS")) {
            SofiaHitsRunner.setStaticParams(config);
            this.promptInfo = SofiaHitsRunner.generatePromptInfoWithDep(config, classInfo, methodInfo);
        } else {
            ExpRunner.setStaticParams(config);
            this.promptInfo = ExpRunner.generatePromptInfoWithDep(config, classInfo, methodInfo);
        }
    }

    public void setPromptInfoWithoutDep(ClassInfo classInfo, MethodInfo methodInfo) throws IOException {
        this.promptInfo = AbstractRunner.generatePromptInfoWithoutDep(config, classInfo, methodInfo);
    }

    public void setFullTestName(String fullTestName) {
        this.fullTestName = fullTestName;
        this.testName = fullTestName.substring(fullTestName.lastIndexOf(".") + 1);
        this.promptInfo.setFullTestName(this.fullTestName);
    }

    public void setTestName(String testName) {
        this.testName = testName;
    }

    public void countToken() {
        for (ChatMessage p : chatMessages) {
            this.tokenCount += TokenCounter.countToken(p.getContent());
        }
    }

    public boolean isExceedMaxTokens() {
        if (this.tokenCount > config.maxPromptTokens) {
            return true;
        } else {
            return false;
        }
    }

}
