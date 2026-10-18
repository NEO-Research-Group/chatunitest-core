package zju.cst.aces.mutation;

import lombok.Data;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.dto.PromptInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Input of MutationAnalyzer: the focal method, the test classes (source code) and how to run PIT.
 */
@Data
public class MutationRequest {
    public String focalClass;           // FQN
    public String methodName;
    public String methodSignature;      // ChatUniTest signature, e.g. "write(int, String, Map, T)"
    public String methodDescriptor;     // JVM descriptor; used only if it matches one of PIT exactly
    /** Test classes to run together: FQN -> source code. They are compiled in a directory of the call. */
    public Map<String, String> tests = new LinkedHashMap<>();
    /** Project classpath (dependencies). targetClassesDir is put before it. */
    public List<String> classPath = new ArrayList<>();
    public String targetClassesDir;     // target/classes: the code that PIT mutates
    public String sourceDir;            // src/main/java (only for PIT's reports)
    public String mutators = "STRONGER";
    public int threads = 2;
    public Integer timeoutConst;        // PIT --timeoutConst (ms); null = PIT default
    public long processTimeoutSeconds = 600;
    public Path workDir;                // classes/, pit/ and pit.log of this call
    public String javaHome;             // JVM for PIT; null = the current one

    /**
     * Request for the focal method of promptInfo with the classpath of the project being tested.
     * workDir = tmpOutput/<artifactId>/mutation/<Class>_<method>_<timestamp>.
     */
    public static MutationRequest from(Config config, PromptInfo promptInfo, Map<String, String> tests) {
        MutationRequest r = new MutationRequest();
        r.focalClass = promptInfo.getFullClassName();
        r.methodName = promptInfo.getMethodName();
        r.methodSignature = promptInfo.getMethodSignature();
        r.methodDescriptor = promptInfo.getMethodDescriptor();
        r.tests.putAll(tests);
        r.classPath.addAll(config.getClassPaths());
        r.targetClassesDir = config.getProject().getBuildPath().toString();
        List<String> roots = config.getProject().getCompileSourceRoots();
        r.sourceDir = roots == null || roots.isEmpty() ? null : roots.get(0);
        r.workDir = config.getTmpOutput().resolve("mutation")
                .resolve(promptInfo.getClassName() + "_" + promptInfo.getMethodName() + "_" + System.nanoTime());
        return r;
    }
}
