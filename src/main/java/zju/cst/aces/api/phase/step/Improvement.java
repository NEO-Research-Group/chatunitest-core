package zju.cst.aces.api.phase.step;

import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.impl.PromptConstructorImpl;

/**
 * Quality improvement (phase 2): asks for a new test class with only new tests for the focal method, given the
 * accumulated green suite and, in mutants mode, its surviving mutants. The PromptInfo of pc must come from
 * PromptInfo.forImprovement (improveMode set, round 0): PromptGenerator then uses quality_improve / quality_generic.
 * The repair of the new class is the usual one (Repair); a restart goes back to the improvement prompt.
 */
public class Improvement {
    private final Config config;

    public Improvement(Config config) {
        this.config = config;
    }

    public void execute(PromptConstructorImpl pc) {
        if (pc.getPromptInfo().getImproveMode() == null) {
            throw new IllegalStateException("Improvement needs a PromptInfo from PromptInfo.forImprovement");
        }
        new TestGeneration(config).execute(pc);
    }
}
