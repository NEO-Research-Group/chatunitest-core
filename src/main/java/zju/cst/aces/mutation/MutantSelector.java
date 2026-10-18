package zju.cst.aces.mutation;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Mutants for the improvement prompt (enfoque.md §3.2): SURVIVED mutants of the focal method (NO_COVERAGE is not
 * passed), the least tried first, at most k. Each appearance in a prompt is an attempt, also if the cycle is
 * discarded; a mutant tried maxAttempts times without being killed is excluded. One selector per run, so that the
 * attempts are kept across cycles (Mutant.id is stable).
 */
public class MutantSelector {
    private final int k;
    private final int maxAttempts;
    private final Map<String, Integer> attempts = new LinkedHashMap<>();

    public MutantSelector(int k, int maxAttempts) {
        this.k = k;
        this.maxAttempts = maxAttempts;
    }

    /** SURVIVED mutants that are not excluded. */
    public List<Mutant> candidates(MutationResult res) {
        return res.mutants.stream()
                .filter(m -> "SURVIVED".equals(m.status))
                .filter(m -> attempts.getOrDefault(m.id, 0) < maxAttempts)
                .collect(Collectors.toList());
    }

    /** Picks up to k candidates (fewest attempts first, then by line) and counts the attempt. */
    public List<Mutant> select(MutationResult res) {
        List<Mutant> chosen = candidates(res).stream()
                .sorted(Comparator.comparingInt((Mutant m) -> attempts.getOrDefault(m.id, 0))
                        .thenComparingInt(m -> m.line)
                        .thenComparing(m -> m.id))
                .limit(k)
                .collect(Collectors.toList());
        for (Mutant m : chosen) {
            attempts.merge(m.id, 1, Integer::sum);
        }
        return chosen;
    }

    public Map<String, Integer> getAttempts() {
        return attempts;
    }

    /** "- line N: `code` -> description (MUTATOR)", as scripts/improve_once.py in CUTMUT (S05). */
    public static String format(List<Mutant> mutants, List<String> sourceLines) {
        StringBuilder sb = new StringBuilder();
        for (Mutant m : mutants) {
            String code = sourceLines != null && m.line > 0 && m.line <= sourceLines.size()
                    ? sourceLines.get(m.line - 1).trim() : "";
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(String.format("- line %d: `%s` -> %s (%s)", m.line, code, m.description, m.mutatorShort));
        }
        return sb.toString();
    }

    /** Score sentence of the improvement prompt, as scripts/improve_once.py in CUTMUT (S05). */
    public static String score(MutationResult res) {
        return String.format(Locale.ROOT, "%d of its %d mutants are detected (mutation score %.2f, %d mutants are not "
                        + "covered by any test), and %d of the %d covered mutants are detected (test strength %.2f).",
                res.detected, res.total, res.ms == null ? 0.0 : res.ms, res.noCoverage,
                res.detected, res.detected + res.survived, res.ts == null ? 0.0 : res.ts);
    }
}
