package zju.cst.aces.dto;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import lombok.Data;

import java.nio.file.Path;
import java.util.*;
import zju.cst.aces.util.JsonResponseProcessor;

@Data
public class PromptInfo {
    public boolean hasDep;
    public String fullClassName;
    public String className;
    public String methodName;
    public String methodSignature;
    public String context; // context with only focal method.
    public String otherMethodBrief;
    public String otherMethodBodies;
    public Map<String, String> constructorDeps = new HashMap<>(); // dependent classes in constructor.
    public Map<String, String> methodDeps = new HashMap<>(); // dependent classes in method parameters and body.
    public Map<String, String> externalConstructorDeps = new HashMap<>(); // dependent classes in constructor.
    public Map<String, String> externalMethodDeps = new HashMap<>(); // dependent classes in constructor.
    public TestMessage errorMsg;
    public String unitTest = "";
    public String fullTestName;
    public Path testPath;
    public Map<String, List<MethodDeclaration>> correctTests = new HashMap<>();
    public Integer testNum;
    public Integer round;
    public List<RoundRecord> records = new ArrayList<>();
    public MethodInfo methodInfo;
    public ClassInfo classInfo;
    public Path methodSlicePath;
    public Integer sliceNum;
    public JsonResponseProcessor.JsonData.Step sliceStep;
    public float coverage=0;//max_coverage
    public int coverage_improve_time=0;
    public String max_coverage_test_code;
    public Integer sofiaActivations=0;
    public String methodDescriptor;
    // Result of the last validation (AbstractRunner.runTest), written to the JSON report.
    // Counts are -1 when the test was not executed (e.g. noExecution or compilation error).
    public String validationOutcome = "NOT_VALIDATED";
    public int testsFound = -1;     // tests run by JUnit (parameterized invocations count separately)
    public int testsPassed = -1;
    public int testsFailures = -1;  // AssertionError (wrong oracle)
    public int testsErrors = -1;    // any other exception
    public int testsPruned = -1;    // @Test methods removed by the pruning before exporting
    public int testsExported = -1;  // @Test methods in the exported suite
    public int timeouts = 0;        // executions that timed out / crashed (summary == null), over all rounds
    public int stagnantRounds = 0;  // repair rounds whose prompt was identical to the previous round's
    // Suite with only the tests that passed in some executed round (failed @Test removed by name). It is validated
    // and exported (PARTIAL_CORRECT_TESTS) only if all the rounds fail, so that green tests are not lost.
    public String greenCandidate;
    public int greenCandidateTests = -1;       // @Test methods in greenCandidate
    public int greenCandidateSourceTests = -1; // @Test methods in the round it comes from
    public int greenCandidateRound = -1;
    public int greenFromRound = -1;            // round of the exported PARTIAL_CORRECT_TESTS suite
    // Quality improvement (phase 2): set only in the PromptInfo of a new class Q_i (see forImprovement).
    // improveMode = Config.IMPROVE_MUTANTS | IMPROVE_GENERIC; null in the generation of S0.
    public String improveMode;
    public String suiteTests;        // the accumulated green suite, as context (not to repeat its tests)
    public String improveMutants;    // surviving mutants, one per line (mutants mode)
    public String improveScore;      // mutation score of the suite, as a sentence (mutants mode)
    public String privateBodies;     // bodies of the private methods of the focal class called by the focal method
    public String improveFce;        // whole focal class (improveFocalContext = fce)
    public int inRoundRestarts = 0;  // repair rounds asked again because the response did not change the test


    /**
     * PromptInfo of a new improvement class (Q_i) for the focal method of s0: the same context (the copy
     * constructor leaves out the external dependencies, the other method bodies and the descriptor), and its own
     * records, correct tests and validation state.
     */
    public static PromptInfo forImprovement(PromptInfo s0, String fullTestName, java.nio.file.Path testPath) {
        PromptInfo q = new PromptInfo(s0);
        q.setExternalConstructorDeps(s0.getExternalConstructorDeps());
        q.setExternalMethodDeps(s0.getExternalMethodDeps());
        q.setOtherMethodBodies(s0.getOtherMethodBodies());
        q.setMethodDescriptor(s0.getMethodDescriptor());
        q.setSofiaActivations(s0.getSofiaActivations());
        q.setTestNum(s0.getTestNum());
        q.setRecords(new ArrayList<>());
        q.setCorrectTests(new HashMap<>());
        q.setErrorMsg(null);
        q.setUnitTest("");
        q.setFullTestName(fullTestName);
        q.setTestPath(testPath);
        return q;
    }


    public PromptInfo(boolean hasDep, String fullClassName, String methodName,
                      String methodSignature, String methodDescriptor) {
        this.hasDep = hasDep;
        this.fullClassName = fullClassName;
        this.className = fullClassName.contains(".") ?
                fullClassName.substring(fullClassName.lastIndexOf(".") + 1) : fullClassName;
        this.methodName = methodName;
        this.methodSignature = methodSignature;
        this.methodDescriptor = methodDescriptor;
    }

    public PromptInfo(){}

    public PromptInfo(PromptInfo p) {
        this.setHasDep(p.isHasDep());
        this.setFullClassName(p.getFullClassName());
        this.setClassName(p.getClassName());
        this.setMethodName(p.getMethodName());
        this.setMethodSignature(p.getMethodSignature());
        this.setContext(p.getContext());
        this.setOtherMethodBrief(p.getOtherMethodBrief());
        this.setConstructorDeps(p.getConstructorDeps());
        this.setMethodDeps(p.getMethodDeps());
        this.setErrorMsg(p.getErrorMsg());
        this.setUnitTest(p.getUnitTest());
        this.setFullTestName(p.getFullTestName());
        this.setTestPath(p.getTestPath());
        this.setCorrectTests(p.getCorrectTests());
        this.setRecords(p.getRecords());
        this.setMethodInfo(p.getMethodInfo());
        this.setClassInfo(p.getClassInfo());
    }

    public void addMethodDeps(String depClassName, String methodDep) {
        if (methodDep == null) {
            return;
        }
        this.methodDeps.put(depClassName, methodDep);
    }

    public void addConstructorDeps(String depClassName, String constructorDep) {
        if (constructorDep == null) {
            return;
        }
        this.constructorDeps.put(depClassName, constructorDep);
    }

    public void addExternalMethodDeps(String depClassName, String methodDep) {
        if (methodDep == null) {
            return;
        }
        this.externalMethodDeps.put(depClassName, methodDep);
    }

    public void addExternalConstructorDeps(String depClassName, String constructorDep) {
        if (constructorDep == null) {
            return;
        }
        this.externalConstructorDeps.put(depClassName, constructorDep);
    }

    public void addCorrectTest(MethodDeclaration m) {
        ClassOrInterfaceDeclaration c = (ClassOrInterfaceDeclaration) m.getParentNode()
                .orElseThrow(() -> new NoSuchElementException("Parent node not found"));
        String className = c.getNameAsString();
        if (this.correctTests.containsKey(className)) {
            this.correctTests.get(className).add(m);
            return;
        } else {
            List<MethodDeclaration> methods = new ArrayList<>();
            methods.add(m);
            this.correctTests.put(className, methods);
        }
    }

    public void addRecord(RoundRecord r) {
        this.records.add(r);
    }

    public String getMethodAndConstructorDepsStrings() {
        return String.join(" ", methodDeps.values()) + String.join(" ", constructorDeps.values());
    }

    public Integer getTokenCount() {
        Integer tokenSum = 0;
        for (RoundRecord r : records) {
            tokenSum += r.getPromptToken() + r.getResponseToken() + r.getDiscardedPromptToken() + r.getDiscardedResponseToken();
        }
        return tokenSum;
    }

    public Integer getInputTokenCount() {
        Integer tokenSum = 0;
        for (RoundRecord r : records) {
            tokenSum += r.getPromptToken() + r.getDiscardedPromptToken();
        }
        return tokenSum;
    }

    public Integer getOutputTokenCount() {
        Integer tokenSum = 0;
        for (RoundRecord r : records) {
            tokenSum += r.getResponseToken() + r.getDiscardedResponseToken();
        }
        return tokenSum;
    }

    /** Keeps the candidate with most tests (the latest on ties). */
    public void offerGreenCandidate(String code, int tests, int sourceTests, int round) {
        if (code == null || tests <= 0 || tests < greenCandidateTests) {
            return;
        }
        greenCandidate = code;
        greenCandidateTests = tests;
        greenCandidateSourceTests = sourceTests;
        greenCandidateRound = round;
    }

    /** Rounds whose response reached the output limit (the code is probably cut). */
    public int countTruncatedResponses(int maxResponseTokens) {
        int count = 0;
        for (RoundRecord r : records) {
            if (r.getResponseToken() >= maxResponseTokens) {
                count++;
            }
        }
        return count;
    }

    public void incrementSofiaActivations() {
        sofiaActivations++;
    }

    public void resetValidationStats(String outcome) {
        validationOutcome = outcome;
        testsFound = -1;
        testsPassed = -1;
        testsFailures = -1;
        testsErrors = -1;
        testsPruned = -1;
        testsExported = -1;
    }
}
