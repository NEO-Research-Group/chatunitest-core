package zju.cst.aces.mutation;

import com.google.gson.GsonBuilder;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Output of MutationAnalyzer. The counts, MS and TS are those of the focal method
 * (mutatedMethod + methodDescription), as scripts/parse_mutations.py in CUTMUT.
 */
@Data
public class MutationResult {
    public enum Status {
        OK,
        EMPTY,              // no @Test in the test classes: PIT is not run
        COMPILE_ERROR,
        GREEN_RUN_FAILED,   // some test fails without mutation (failingTests has the names)
        TIMEOUT,            // the PIT process took longer than processTimeoutSeconds
        PIT_ERROR
    }

    public Status status;
    public String mutators;
    public List<String> testClasses = new ArrayList<>();

    // Focal method
    public String methodDescription;    // chosen overload; empty if none
    public boolean descOk = true;       // false: ambiguous overload, all of them are counted
    public int overloads;
    public int total;
    public int killed;
    public int survived;
    public int noCoverage;
    public int timedOut;
    public int memoryError;
    public int runError;
    public int nonViable;
    public int other;
    public int detected;                // KILLED + TIMED_OUT + MEMORY_ERROR + RUN_ERROR + NON_VIABLE
    public Double ms;                   // detected / total
    public Double ts;                   // detected / (detected + survived)
    public List<Mutant> mutants = new ArrayList<>();

    // Focal class (all its methods, inner classes included)
    public int clsTotal;
    public int clsDetected;
    public int clsNoCoverage;

    // GREEN_RUN_FAILED: test class -> names of the @Test that fail without mutation
    public Map<String, Set<String>> failingTests = new LinkedHashMap<>();
    public boolean failuresAttributed = true;   // false: some failure is not of a test method
    // analyzeWithGreenPruning: tests removed and resulting sources (FQN -> code; null = class dropped)
    public Map<String, Set<String>> prunedTests = new LinkedHashMap<>();
    public int greenPrunes;
    public Map<String, String> finalTests;

    public List<String> compileErrors = new ArrayList<>();
    public int exitCode;
    public String logTail;
    public String reportDir;
    public long compileMillis;
    public long pitMillis;
    public long totalMillis;

    public String toJson() {
        return new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(this);
    }
}
