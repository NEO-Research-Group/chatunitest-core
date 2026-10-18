package zju.cst.aces.api.config;

import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import zju.cst.aces.api.PreProcess;
import zju.cst.aces.api.Project;
import com.github.javaparser.JavaParser;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.Getter;
import lombok.Setter;
import okhttp3.OkHttpClient;
import zju.cst.aces.api.Validator;
import zju.cst.aces.api.impl.LoggerImpl;
import zju.cst.aces.api.Logger;
import zju.cst.aces.api.impl.ValidatorImpl;
import zju.cst.aces.dto.OCM;
import zju.cst.aces.parser.ProjectParser;
import zju.cst.aces.prompt.template.PromptTemplate;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Getter
@Setter
public class Config {
    /** Method selection (MethodRunner): benchmark = external deps AND branches (Expert Systems benchmark). */
    public static final String METHOD_FILTER_BENCHMARK = "benchmark";
    public static final String METHOD_FILTER_BRANCHES = "branches";
    public static final String METHOD_FILTER_EXTERNAL_DEPS = "externalDeps";
    public static final String METHOD_FILTER_NONE = "none";
    public static final List<String> METHOD_FILTERS = Arrays.asList(
            METHOD_FILTER_BENCHMARK, METHOD_FILTER_BRANCHES, METHOD_FILTER_EXTERNAL_DEPS, METHOD_FILTER_NONE);
    /** What to do when a repair prompt is identical to the previous round's (TestGeneration). none = Expert Systems. */
    public static final String STAGNATION_NONE = "none";
    public static final String STAGNATION_TEMPERATURE = "temperature"; // same prompt, stagnationTemperature, no seed
    public static final String STAGNATION_RESTART = "restart";         // initial prompt, stagnationTemperature, no seed
    public static final String STAGNATION_FEEDBACK = "feedback";       // same prompt plus a note that the fix did not work
    public static final List<String> STAGNATION_STRATEGIES = Arrays.asList(
            STAGNATION_NONE, STAGNATION_TEMPERATURE, STAGNATION_RESTART, STAGNATION_FEEDBACK);
    /** Prompt of the quality improvement (phase 2): surviving mutants, or the generic control without them. */
    public static final String IMPROVE_MUTANTS = "mutants";
    public static final String IMPROVE_GENERIC = "generic";
    public static final List<String> IMPROVE_PROMPT_TYPES = Arrays.asList(IMPROVE_MUTANTS, IMPROVE_GENERIC);
    /** Extra focal-class context of the improvement prompt: bodies of the private methods, or the whole class. */
    public static final String IMPROVE_CONTEXT_BODIES = "bodies";
    public static final String IMPROVE_CONTEXT_FCE = "fce";
    public static final List<String> IMPROVE_FOCAL_CONTEXTS = Arrays.asList(IMPROVE_CONTEXT_BODIES, IMPROVE_CONTEXT_FCE);

    public String date;
    public Gson GSON;
    public Project project;
    public JavaParser parser;
    public PreProcess preProcessor;
    public JavaParserFacade parserFacade;
    public List<String> classPaths;
    public Path promptPath;
    public Properties properties;
    public String url;
    public String[] apiKeys;
    public Logger logger;
    public String OS;
    public boolean stopWhenSuccess;
    public boolean noExecution;
    public boolean enableMultithreading;
    public boolean enableRuleRepair;
    public boolean enableMerge;
    public boolean enableObfuscate;
    public String[] obfuscateGroupIds;
    public int maxThreads;
    public int classThreads;
    public int methodThreads;
    public int testNumber;
    public int maxRounds;
    public int maxPromptTokens;
    public int maxResponseTokens;
    public int minErrorTokens;
    public int sleepTime;
    public int dependencyDepth;
    public Model model;
    public Double temperature;
    public int topP;
    public int frequencyPenalty;
    public int presencePenalty;
    public Path testOutput;
    public Path counterExamplePath;
    public Path tmpOutput;
    public Path compileOutputPath;
    public Path parseOutput;
    public Path errorOutput;
    public Path classNameMapPath;
    public Path historyPath;
    public Path examplePath;
    public Path symbolFramePath;

    public int max_coverage_improve_time;
    public int sampleSize;
    public String proxy;
    public String hostname;
    public String port;
    public OkHttpClient client;
    public AtomicInteger sharedInteger = new AtomicInteger(0);
    public AtomicInteger jobCount = new AtomicInteger(0);
    public AtomicInteger completedJobCount = new AtomicInteger(0);
    public static Map<String, Map<String, String>> classMapping = new HashMap<>();
    public static Map<String, TreeSet<String>> objectConstructionCode = new HashMap<>();
    public static OCM ocm = new OCM();
    public Validator validator;
    public String pluginSign;
    public String phaseType;
    public String module;
    public boolean useSlice;
    public boolean useExtra;
    public List<String> dependencyPaths;
    public boolean generateJsonReport;
    public boolean enablePrune;
    public String methodFilter;
    public String stagnationStrategy;
    public double stagnationTemperature;
    // Quality improvement guided by mutation testing (phase 2, CUTMUT)
    public boolean enableQualityImprove;
    public int maxQualityCycles;
    public int maxRepairPerCycle;
    public int maxMutantsPerPrompt;
    public int maxMutantAttempts;
    public int stagnationCycles;
    public String mutators;
    public int pitTimeout;
    public String improvePromptType;
    public String improveFocalContext;

    @Getter
    @Setter
    public static class ConfigBuilder {
        public String date;
        public Project project;
        public JavaParser parser;
        public PreProcess preProcessor;
        public JavaParserFacade parserFacade;
        public List<String> classPaths;
        public Path promptPath;
        public Properties properties;
        public String url;
        public String[] apiKeys;
        public Logger logger;
        public String OS = System.getProperty("os.name").toLowerCase();
        public boolean stopWhenSuccess = true;
        public boolean noExecution = false;
        public boolean enableMultithreading = true;
        public boolean enableRuleRepair = true;
        public boolean enableMerge = true;
        public boolean enableObfuscate = false;
        public String[] obfuscateGroupIds;
        public int maxThreads = Runtime.getRuntime().availableProcessors() * 5;
        public int classThreads = (int) Math.ceil((double)  this.maxThreads / 10);
        public int methodThreads = (int) Math.ceil((double) this.maxThreads / this.classThreads);
        public int testNumber = 5;
        public int maxRounds = 5;
        public int maxPromptTokens = 2600;
        public int maxResponseTokens = 1024;
        public int minErrorTokens = 500;
        public int sleepTime = 0;
        public int dependencyDepth = 1;
        public Model model = Model.GPT_3_5_TURBO;
        public Double temperature = 0.5;
        public int topP = 1;
        public int frequencyPenalty = 0;
        public int presencePenalty = 0;
        public Path testOutput;
        public Path counterExamplePath;
        public Path tmpOutput = Paths.get(System.getProperty("java.io.tmpdir"), "chatunitest-info");
        public Path parseOutput;
        public Path compileOutputPath;
        public Path errorOutput;
        public Path classNameMapPath;
        public Path historyPath;
        public Path examplePath;
        public Path symbolFramePath;
        public String proxy = "null:-1";
        public String hostname = "null";
        public String port = "-1";
        public OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(5, TimeUnit.MINUTES)
                .readTimeout(5, TimeUnit.MINUTES)
                .build();
        public int max_coverage_improve_time=maxRounds;
        public int sampleSize = 10;
        public Validator validator;
        public String pluginSign;
        public String phaseType; //TODO
        public String module = "";
        public boolean useSlice;
        public boolean useExtra;
        public List<String> dependencyPaths;
        public boolean generateJsonReport = false;
        public boolean enablePrune = false;
        public String methodFilter = METHOD_FILTER_BENCHMARK;
        public String stagnationStrategy = STAGNATION_NONE;
        public double stagnationTemperature = 0.7;
        public boolean enableQualityImprove = false;
        public int maxQualityCycles = 5;
        public int maxRepairPerCycle = 2;
        public int maxMutantsPerPrompt = 5;
        public int maxMutantAttempts = 2;
        public int stagnationCycles = 2;
        public String mutators = "STRONGER";
        public int pitTimeout = 600;
        public String improvePromptType = IMPROVE_MUTANTS;
        public String improveFocalContext = IMPROVE_CONTEXT_BODIES;

        public ConfigBuilder(Project project) {
            initDefault(project);
        }

        public void initDefault(Project project) {
            this.date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss")).toString();
            this.project = project;
            assert(project.getClassPaths() != null);
            this.classPaths = project.getClassPaths();

            this.logger = new LoggerImpl();
            this.parser = new JavaParser();
            JavaSymbolSolver symbolSolver = getSymbolSolver();
            parser.getParserConfiguration().setSymbolResolver(symbolSolver);
            ProjectParser.setLanguageLevel(parser.getParserConfiguration());

            this.properties("config.properties");

            this.maxPromptTokens = this.model.getDefaultConfig().getContextLength() * 2 / 3;
            this.maxResponseTokens = 1024;
            this.minErrorTokens = this.maxPromptTokens * 1 / 3 - this.maxResponseTokens;
            if (this.minErrorTokens < 0) {
                this.minErrorTokens = 512;
            }

            Project parent = project.getParent();
            while(parent != null && parent.getBasedir() != null) {
                this.tmpOutput = this.tmpOutput.resolve(parent.getArtifactId());
                parent = parent.getParent();
            }
            this.tmpOutput = this.tmpOutput.resolve(project.getArtifactId());
            this.compileOutputPath = this.tmpOutput.resolve("build");
            this.parseOutput = this.tmpOutput.resolve("class-info");
            this.errorOutput = this.tmpOutput.resolve("error-message");
            this.classNameMapPath = this.tmpOutput.resolve("classNameMapping.json");
            this.historyPath = this.tmpOutput.resolve("history" + this.date);
            this.symbolFramePath = this.tmpOutput.resolve("symbolFrames.json");
            this.counterExamplePath = project.getBasedir().toPath().resolve("smartut-tests");
            this.testOutput = project.getBasedir().toPath().resolve("chatunitest-tests");
            this.validator = new ValidatorImpl(this.testOutput, this.compileOutputPath,
                    this.project.getBasedir().toPath().resolve("target"), this.classPaths);
        }

        public ConfigBuilder maxThreads(int maxThreads) {
            if (maxThreads <= 0) {
                this.maxThreads = Runtime.getRuntime().availableProcessors() * 5;
            } else {
                this.maxThreads = maxThreads;
            }
            this.classThreads = (int) Math.ceil((double)  this.maxThreads / 10);
            this.methodThreads = (int) Math.ceil((double) this.maxThreads / this.classThreads);
            if (this.stopWhenSuccess == false) {
                this.methodThreads = (int) Math.ceil((double)  this.methodThreads / this.testNumber);
            }
            return this;
        }

        public ConfigBuilder proxy(String proxy) {
            setProxy(proxy);
            return this;
        }
        public ConfigBuilder max_coverage_improve_time(int max_coverage_improve_time){
            this.max_coverage_improve_time=max_coverage_improve_time;
            return this;
        }

        public ConfigBuilder sampleSize(int sampleSize) {
            this.sampleSize = sampleSize;
            return this;
        }
        public ConfigBuilder tmpOutput(Path tmpOutput) {
            this.tmpOutput = tmpOutput;
            Project parent = project.getParent();
            while(parent != null && parent.getBasedir() != null) {
                this.tmpOutput = this.tmpOutput.resolve(parent.getArtifactId());
                parent = parent.getParent();
            }
            this.tmpOutput = this.tmpOutput.resolve(project.getArtifactId());
            this.compileOutputPath = this.tmpOutput.resolve("build");
            this.parseOutput = this.tmpOutput.resolve("class-info");
            this.errorOutput = this.tmpOutput.resolve("error-message");
            this.classNameMapPath = this.tmpOutput.resolve("classNameMapping.json");
            this.historyPath = this.tmpOutput.resolve("history" + this.date);
            this.symbolFramePath = this.tmpOutput.resolve("symbolFrames.json");
            this.validator = new ValidatorImpl(this.testOutput, this.compileOutputPath,
                    this.project.getBasedir().toPath().resolve("target"), this.classPaths);
            return this;
        }
        public ConfigBuilder CounterExamplePath(Path counterExamplePath) {
            if (counterExamplePath == null) {
                this.counterExamplePath = project.getBasedir().toPath().resolve("smartut-tests");
            } else {
                this.counterExamplePath = counterExamplePath;
                Project parent = project.getParent();
                while(parent != null && parent.getBasedir() != null) {
                    this.counterExamplePath = this.counterExamplePath.resolve(parent.getArtifactId());
                    parent = parent.getParent();
                }
                this.counterExamplePath = this.counterExamplePath.resolve(project.getArtifactId());
            }
            return this;
        }
        public ConfigBuilder project(Project project) {
            this.project = project;
            return this;
        }

        public ConfigBuilder pluginSign(String pluginSign){
            this.pluginSign=pluginSign;
            return this;
        }

        public ConfigBuilder phaseType(String phaseType){
            this.phaseType=phaseType;
            return this;
        }

        public ConfigBuilder useSlice(boolean useSlice){
            this.useSlice=useSlice;
            return this;
        }

        public ConfigBuilder useExtra(boolean useExtra){
            this.useExtra=useExtra;
            return this;
        }

        public ConfigBuilder promptPath(File promptPath) {
            if (promptPath != null) {
                this.promptPath = promptPath.toPath();
            }
            return this;
        }

        public ConfigBuilder parser(JavaParser parser) {
            this.parser = parser;
            return this;
        }

        public ConfigBuilder preProcessor(PreProcess preProcessor) {
            this.preProcessor = preProcessor;
            return this;
        }

        public ConfigBuilder parserFacade(JavaParserFacade parserFacade) {
            this.parserFacade = parserFacade;
            return this;
        }

        public ConfigBuilder classPaths(List<String> classPaths) {
            this.classPaths = classPaths;
            this.validator = new ValidatorImpl(this.testOutput, this.compileOutputPath,
                    this.project.getBasedir().toPath().resolve("target"), this.classPaths);
            return this;
        }

        public ConfigBuilder logger(Logger logger) {
            this.logger = logger;
            return this;
        }

        public ConfigBuilder OS(String OS) {
            this.OS = OS;
            return this;
        }

        public ConfigBuilder stopWhenSuccess(boolean stopWhenSuccess) {
            this.stopWhenSuccess = stopWhenSuccess;
            return this;
        }

        public ConfigBuilder noExecution(boolean noExecution) {
            this.noExecution = noExecution;
            return this;
        }

        public ConfigBuilder enableMultithreading(boolean enableMultithreading) {
            this.enableMultithreading = enableMultithreading;
            return this;
        }

        public ConfigBuilder enableRuleRepair(boolean enableRuleRepair) {
            this.enableRuleRepair = enableRuleRepair;
            return this;
        }

        public ConfigBuilder enableMerge(boolean enableMerge) {
            this.enableMerge = enableMerge;
            return this;
        }

        public ConfigBuilder enableObfuscate(boolean enableObfuscate) {
            this.enableObfuscate = enableObfuscate;
            return this;
        }

        public ConfigBuilder properties(String configFile) {
            try {
                Properties properties = new Properties();
                InputStream inputStream = PromptTemplate.class.getClassLoader().getResourceAsStream(configFile);
                properties.load(inputStream);
                this.properties = properties;
                return this;
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException("Failed to load properties file: " + configFile);
            }
        }

        public ConfigBuilder obfuscateGroupIds(String[] obfuscateGroupIds) {
            this.obfuscateGroupIds = obfuscateGroupIds;
            return this;
        }

        public ConfigBuilder classThreads(int classThreads) {
            this.classThreads = classThreads;
            return this;
        }

        public ConfigBuilder methodThreads(int methodThreads) {
            this.methodThreads = methodThreads;
            return this;
        }

        public ConfigBuilder url(String url) {
            if (!this.model.getModelName().contains("gpt-4") && !this.model.getModelName().contains("gpt-3.5") && url.equals("https://api.gptsapi.net/v1/chat/completions")) {
                throw new RuntimeException("Invalid url for model: " + this.model + ". Please configure the url in plugin configuration.");
            }
            this.url = url;
            this.model.getDefaultConfig().setUrl(url);
            return this;
        }

        public ConfigBuilder apiKeys(String[] apiKeys) {
            this.apiKeys = apiKeys;
            return this;
        }

        public ConfigBuilder testNumber(int testNumber) {
            this.testNumber = testNumber;
            return this;
        }

        public ConfigBuilder maxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
            return this;
        }

        public ConfigBuilder maxPromptTokens(int maxPromptTokens) {
            if (maxPromptTokens > 0) {
                this.maxPromptTokens = maxPromptTokens;
            }
            return this;
        }

        public ConfigBuilder maxResponseTokens(int maxResponseTokens) {
            this.maxResponseTokens = maxResponseTokens;
            return this;
        }

        public ConfigBuilder minErrorTokens(int minErrorTokens) {
            this.minErrorTokens = minErrorTokens;
            return this;
        }

        public ConfigBuilder sleepTime(int sleepTime) {
            this.sleepTime = sleepTime;
            return this;
        }

        public ConfigBuilder dependencyDepth(int dependencyDepth) {
            this.dependencyDepth = dependencyDepth;
            return this;
        }

        public ConfigBuilder model(String model) {
            this.model = Model.fromString(model);
            this.maxPromptTokens = this.model.getDefaultConfig().getContextLength() * 2 / 3;
            this.maxResponseTokens = 1024;
            this.minErrorTokens = this.maxPromptTokens * 1 / 2 - this.maxResponseTokens;
            if (this.minErrorTokens < 0) {
                this.minErrorTokens = 512;
            }
            return this;
        }

        public ConfigBuilder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public ConfigBuilder topP(int topP) {
            this.topP = topP;
            return this;
        }

        public ConfigBuilder frequencyPenalty(int frequencyPenalty) {
            this.frequencyPenalty = frequencyPenalty;
            return this;
        }

        public ConfigBuilder presencePenalty(int presencePenalty) {
            this.presencePenalty = presencePenalty;
            return this;
        }

        public ConfigBuilder testOutput(Path testOutput) {
            if (testOutput == null) {
                this.testOutput = project.getBasedir().toPath().resolve("chatunitest-tests");
            } else {
                this.testOutput = testOutput;
                Project parent = project.getParent();
                while(parent != null && parent.getBasedir() != null) {
                    this.testOutput = this.testOutput.resolve(parent.getArtifactId());
                    parent = parent.getParent();
                }
                this.testOutput = this.testOutput.resolve(project.getArtifactId());
            }
            return this;
        }

        public ConfigBuilder compileOutputPath(Path compileOutputPath) {
            this.compileOutputPath = compileOutputPath;
            return this;
        }

        public ConfigBuilder parseOutput(Path parseOutput) {
            this.parseOutput = parseOutput;
            return this;
        }

        public ConfigBuilder errorOutput(Path errorOutput) {
            this.errorOutput = errorOutput;
            return this;
        }

        public ConfigBuilder classNameMapPath(Path classNameMapPath) {
            this.classNameMapPath = classNameMapPath;
            return this;
        }

        public ConfigBuilder examplePath(Path examplePath) {
            this.examplePath = examplePath;
            return this;
        }

        public ConfigBuilder symbolFramePath(Path symbolFramePath) {
            this.symbolFramePath = symbolFramePath;
            return this;
        }

        public ConfigBuilder hostname(String hostname) {
            this.hostname = hostname;
            return this;
        }

        public ConfigBuilder port(String port) {
            this.port = port;
            return this;
        }

        public ConfigBuilder client(OkHttpClient client) {
            this.client = client;
            return this;
        }

        public ConfigBuilder dependencyPaths(List<String> dependencyPaths){
            this.dependencyPaths = new ArrayList<>(dependencyPaths);
            return this;
        }

        public void setDependencyPaths(List<String> dependencyPaths){
            this.dependencyPaths = new ArrayList<>(dependencyPaths);
        }

        public ConfigBuilder generateJsonReport(boolean generateJsonReport) {
            this.generateJsonReport = generateJsonReport;
            return this;
        }

        public ConfigBuilder module(String module) {
            this.module = module;
            return this;
        }

        public ConfigBuilder enablePrune(boolean enablePrune) {
            this.enablePrune = enablePrune;
            return this;
        }

        public ConfigBuilder methodFilter(String methodFilter) {
            this.methodFilter = methodFilter;
            return this;
        }

        public ConfigBuilder stagnationStrategy(String stagnationStrategy) {
            this.stagnationStrategy = stagnationStrategy;
            return this;
        }

        public ConfigBuilder stagnationTemperature(double stagnationTemperature) {
            this.stagnationTemperature = stagnationTemperature;
            return this;
        }

        public ConfigBuilder enableQualityImprove(boolean enableQualityImprove) {
            this.enableQualityImprove = enableQualityImprove;
            return this;
        }

        public ConfigBuilder maxQualityCycles(int maxQualityCycles) {
            this.maxQualityCycles = maxQualityCycles;
            return this;
        }

        public ConfigBuilder maxRepairPerCycle(int maxRepairPerCycle) {
            this.maxRepairPerCycle = maxRepairPerCycle;
            return this;
        }

        public ConfigBuilder maxMutantsPerPrompt(int maxMutantsPerPrompt) {
            this.maxMutantsPerPrompt = maxMutantsPerPrompt;
            return this;
        }

        public ConfigBuilder maxMutantAttempts(int maxMutantAttempts) {
            this.maxMutantAttempts = maxMutantAttempts;
            return this;
        }

        public ConfigBuilder stagnationCycles(int stagnationCycles) {
            this.stagnationCycles = stagnationCycles;
            return this;
        }

        public ConfigBuilder mutators(String mutators) {
            this.mutators = mutators;
            return this;
        }

        public ConfigBuilder pitTimeout(int pitTimeout) {
            this.pitTimeout = pitTimeout;
            return this;
        }

        public ConfigBuilder improvePromptType(String improvePromptType) {
            this.improvePromptType = improvePromptType;
            return this;
        }

        public ConfigBuilder improveFocalContext(String improveFocalContext) {
            this.improveFocalContext = improveFocalContext;
            return this;
        }

        public void setProxy(String proxy) {
            this.proxy = proxy;
            setProxyStr();
            if (!hostname.equals("null") && !port.equals("-1")) {
                setClinetwithProxy();
            } else {
                setClinet();
            }
        }

        public void setProxyStr() {
            this.hostname = this.proxy.split(":")[0];
            this.port = this.proxy.split(":")[1];
        }

        public void setClinet() {
            this.client = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.MINUTES)
                    .writeTimeout(5, TimeUnit.MINUTES)
                    .readTimeout(5, TimeUnit.MINUTES)
                    .build();
        }

        public void setClinetwithProxy() {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(this.hostname, Integer.parseInt(this.port)));
            this.client = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.MINUTES)
                    .writeTimeout(5, TimeUnit.MINUTES)
                    .readTimeout(5, TimeUnit.MINUTES)
                    .proxy(proxy)
                    .build();
        }

        public void setValidator(Validator validator) {
            this.validator = validator;
        }

        public JavaSymbolSolver getSymbolSolver() {
            CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
            combinedTypeSolver.add(new ReflectionTypeSolver());
            for (String dep : this.getClassPaths()) {
                try {
                    File depFile = new File(dep);
                    if (!depFile.exists() || !dep.endsWith("jar")) {
                        continue;
                    }
                    combinedTypeSolver.add(new JarTypeSolver(depFile));
                } catch (Exception e) {
                    this.getLogger().warn(e.getMessage());
                    this.getLogger().debug(e.getMessage());
                }
            }
            for (String src : this.getProject().getCompileSourceRoots()) { // TODO: remove MavenProject
                if (new File(src).exists()) {
                    combinedTypeSolver.add(new JavaParserTypeSolver(src));
                }
            }
            JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);
            this.setParserFacade(JavaParserFacade.get(combinedTypeSolver));
            return symbolSolver;
        }

        public Config build() {
            Config config = new Config();
            config.setDate(this.date);
            config.setGSON(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create());
            config.setProject(this.project);
            config.setParser(this.parser);
            config.setPreProcessor(this.preProcessor);
            config.setParserFacade(this.parserFacade);
            config.setClassPaths(this.classPaths);
            config.setPromptPath(this.promptPath);
            config.setProperties(this.properties);
            config.setUrl(this.url);
            config.setApiKeys(this.apiKeys);
            config.setOS(this.OS);
            config.setStopWhenSuccess(this.stopWhenSuccess);
            config.setNoExecution(this.noExecution);
            config.setEnableMultithreading(this.enableMultithreading);
            config.setEnableRuleRepair(this.enableRuleRepair);
            config.setEnableMerge(this.enableMerge);
            config.setEnableObfuscate(this.enableObfuscate);
            config.setObfuscateGroupIds(this.obfuscateGroupIds);
            config.setMaxThreads(this.maxThreads);
            config.setClassThreads(this.classThreads);
            config.setMethodThreads(this.methodThreads);
            config.setTestNumber(this.testNumber);
            config.setMaxRounds(this.maxRounds);
            config.setMaxPromptTokens(this.maxPromptTokens);
            config.setMaxResponseTokens(this.maxResponseTokens);
            config.setMinErrorTokens(this.minErrorTokens);
            config.setSleepTime(this.sleepTime);
            config.setDependencyDepth(this.dependencyDepth);
            config.setModel(this.model);
            config.setTemperature(this.temperature);
            config.setTopP(this.topP);
            config.setFrequencyPenalty(this.frequencyPenalty);
            config.setPresencePenalty(this.presencePenalty);
            config.setTestOutput(this.testOutput);
            config.setCounterExamplePath(this.counterExamplePath);
            config.setTmpOutput(this.tmpOutput);
            config.setCompileOutputPath(this.compileOutputPath);
            config.setParseOutput(this.parseOutput);
            config.setErrorOutput(this.errorOutput);
            config.setClassNameMapPath(this.classNameMapPath);
            config.setHistoryPath(this.historyPath);
            config.setExamplePath(this.examplePath);
            config.setSymbolFramePath(this.symbolFramePath);
            config.setProxy(this.proxy);
            config.setHostname(this.hostname);
            config.setPort(this.port);
            config.setClient(this.client);
            config.setLogger(this.logger);
            config.setValidator(this.validator);
            config.setPluginSign(this.pluginSign);
            config.setPhaseType(this.phaseType);
            config.setUseSlice(this.useSlice);
            config.setUseExtra(this.useExtra);
            config.setMax_coverage_improve_time(this.max_coverage_improve_time);
            config.setDependencyPaths(this.dependencyPaths);
            config.setGenerateJsonReport(this.generateJsonReport);
            config.setSampleSize(this.sampleSize);
            config.setModule(this.module);
            config.setEnablePrune(this.enablePrune);
            if (!METHOD_FILTERS.contains(this.methodFilter)) {
                throw new IllegalArgumentException("Unknown methodFilter '" + this.methodFilter + "'. Valid values: " + METHOD_FILTERS);
            }
            config.setMethodFilter(this.methodFilter);
            if (!STAGNATION_STRATEGIES.contains(this.stagnationStrategy)) {
                throw new IllegalArgumentException("Unknown stagnationStrategy '" + this.stagnationStrategy + "'. Valid values: " + STAGNATION_STRATEGIES);
            }
            config.setStagnationStrategy(this.stagnationStrategy);
            config.setStagnationTemperature(this.stagnationTemperature);
            config.setEnableQualityImprove(this.enableQualityImprove);
            config.setMaxQualityCycles(this.maxQualityCycles);
            config.setMaxRepairPerCycle(this.maxRepairPerCycle);
            config.setMaxMutantsPerPrompt(this.maxMutantsPerPrompt);
            config.setMaxMutantAttempts(this.maxMutantAttempts);
            config.setStagnationCycles(this.stagnationCycles);
            if (this.mutators == null || this.mutators.trim().isEmpty()) {
                throw new IllegalArgumentException("mutators must be explicit (e.g. STRONGER, ALL)");
            }
            config.setMutators(this.mutators.trim());
            config.setPitTimeout(this.pitTimeout);
            if (!IMPROVE_PROMPT_TYPES.contains(this.improvePromptType)) {
                throw new IllegalArgumentException("Unknown improvePromptType '" + this.improvePromptType + "'. Valid values: " + IMPROVE_PROMPT_TYPES);
            }
            config.setImprovePromptType(this.improvePromptType);
            if (!IMPROVE_FOCAL_CONTEXTS.contains(this.improveFocalContext)) {
                throw new IllegalArgumentException("Unknown improveFocalContext '" + this.improveFocalContext + "'. Valid values: " + IMPROVE_FOCAL_CONTEXTS);
            }
            config.setImproveFocalContext(this.improveFocalContext);
            return config;
        }
    }

    public String getRandomKey() {
        Random rand = new Random();
        if (apiKeys.length == 0) {
            throw new RuntimeException("apiKeys is null!");
        }
        String apiKey = apiKeys[rand.nextInt(apiKeys.length)];
        return apiKey;
    }

    public void print() {
        logger.info("\n========================== Configuration ==========================\n");
        logger.info("PluginSign >>>>"+this.getPluginSign() );
        logger.info(" Multithreading >>>> " + this.isEnableMultithreading());
        if (this.isEnableMultithreading()) {
            logger.info(" - Class threads: " + this.getClassThreads() + ", Method threads: " + this.getMethodThreads());
        }
        logger.info(" Stop when success >>>> " + this.isStopWhenSuccess());
        logger.info(" No execution >>>> " + this.isNoExecution());
        logger.info(" Enable Merge >>>> " + this.isEnableMerge());
        logger.info(" --- ");
        logger.info(" TestOutput Path >>> " + this.getTestOutput());
        logger.info(" TmpOutput Path >>> " + this.getTmpOutput());
        logger.info(" CounterExample Path >>> " + this.getCounterExamplePath());
        logger.info(" Prompt path >>> " + this.getPromptPath());
        logger.info(" Example path >>> " + this.getExamplePath());
        logger.info(" --- ");
        logger.info(" Model >>> " + this.getModel());
        logger.info(" Url >>> " + this.getUrl());
        logger.info(" MaxPromptTokens >>> " + this.getMaxPromptTokens());
        logger.info(" MaxResponseTokens >>> " + this.getMaxResponseTokens());
        logger.info(" MinErrorTokens >>> " + this.getMinErrorTokens());
        logger.info(" MaxThreads >>> " + this.getMaxThreads());
        logger.info(" TestNumber >>> " + this.getTestNumber());
        logger.info(" MaxRounds >>> " + this.getMaxRounds());
        logger.info(" MinErrorTokens >>> " + this.getMinErrorTokens());
        logger.info(" MaxPromptTokens >>> " + this.getMaxPromptTokens());
        logger.info(" SleepTime >>> " + this.getSleepTime());
        logger.info(" DependencyDepth >>> " + this.getDependencyDepth());
        logger.info(" SampleSize >>> " + this.getSampleSize());
        logger.info(" PhaseType >>> " + this.phaseType);
        logger.info(" Module >>>> " + this.getModule());
        logger.info(" Enable Prune >>>> " + this.isEnablePrune());
        logger.info(" Method Filter >>>> " + this.getMethodFilter());
        logger.info(" Stagnation Strategy >>>> " + this.getStagnationStrategy()
                + (STAGNATION_NONE.equals(this.getStagnationStrategy()) ? "" : " (temperature " + this.getStagnationTemperature() + ")"));
        logger.info(" Quality Improve >>>> " + this.isEnableQualityImprove());
        if (this.isEnableQualityImprove()) {
            logger.info(" - Prompt: " + this.getImprovePromptType() + ", focal context: " + this.getImproveFocalContext()
                    + ", mutators: " + this.getMutators() + ", PIT timeout: " + this.getPitTimeout() + " s");
            logger.info(" - Cycles: " + this.getMaxQualityCycles() + ", repairs per cycle: " + this.getMaxRepairPerCycle()
                    + ", mutants per prompt: " + this.getMaxMutantsPerPrompt() + ", attempts per mutant: "
                    + this.getMaxMutantAttempts() + ", stagnation cycles: " + this.getStagnationCycles());
        }
        logger.info("\n===================================================================\n");
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public int getSampleSize() {
        return sampleSize;
    }

    public void setSampleSize(int sampleSize) {
        this.sampleSize = sampleSize;
    }
}
