package zju.cst.aces.mutation;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * A PIT mutant of the focal method, read from mutations.xml.
 */
@Data
public class Mutant {
    /**
     * Stable id across cycles: mutatedClass|mutatedMethod|methodDescription|line|mutator|indexes.
     * The focal class does not change between cycles, so PIT's instruction indexes are deterministic and
     * tell apart mutants with the same line and mutator. The description is not part of the id.
     */
    public String id;
    public String mutatedClass;
    public String mutatedMethod;
    public String methodDescription;
    public int line;
    public String mutator;          // full class name of the mutator
    public String mutatorShort;     // e.g. RemoveConditionalMutator_EQUAL_ELSE
    public String description;      // PIT description, as passed to the LLM
    public String indexes;
    public String status;           // KILLED, SURVIVED, NO_COVERAGE, TIMED_OUT, ...
    public boolean detected;
    public List<String> killingTests = new ArrayList<>();   // TestClass#method (fullMutationMatrix)
    public int coveringCount;       // tests that cover it: killing + succeeding
}
