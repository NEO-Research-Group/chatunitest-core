package zju.cst.aces.mutation;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Targets of the improvement prompt (enfoque.md §3.2, E2): each SURVIVED mutant of the focal method, and each block
 * of contiguous lines whose mutants are NO_COVERAGE, minus the excluded mutants (e.g. the equivalent ones). At most k
 * per prompt, SURVIVED and blocks alternated (S, B, S, B...), the least tried first. Each appearance in a prompt is an
 * attempt for every mutant of the target, also if the cycle is discarded; a mutant tried maxAttempts times without
 * being killed is no longer a target. The attempts are kept per mutant (Mutant.id is stable), so a block can shrink
 * between cycles. One selector per arm: the generic arm selects in the same way without showing the targets, so that
 * both arms stop by the same rule (no target left).
 */
public class MutantSelector {
    public static final String SURVIVED = "SURVIVED";
    public static final String NC_BLOCK = "NC_BLOCK";
    static final int BLOCK_EXAMPLES = 2;

    /** A SURVIVED mutant, or a block of lines from-to that no test executes (its NO_COVERAGE mutants). */
    public static class Target {
        public final String type;
        public final List<Mutant> mutants;
        public final int from;
        public final int to;

        Target(String type, List<Mutant> mutants) {
            this.type = type;
            this.mutants = mutants;
            this.from = mutants.stream().mapToInt(m -> m.line).min().orElse(0);
            this.to = mutants.stream().mapToInt(m -> m.line).max().orElse(0);
        }

        public String lines() {
            return from == to ? String.valueOf(from) : from + "-" + to;
        }

        public List<String> ids() {
            return mutants.stream().map(m -> m.id).collect(Collectors.toList());
        }

        /** For the JSON report: type, lines and mutant ids. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("lines", lines());
            m.put("mutants", ids());
            return m;
        }
    }

    private final int k;
    private final int maxAttempts;
    private final Set<String> excluded;
    private final Map<String, Integer> attempts = new LinkedHashMap<>();

    public MutantSelector(int k, int maxAttempts, Set<String> excluded) {
        this.k = k;
        this.maxAttempts = maxAttempts;
        this.excluded = excluded == null ? Collections.emptySet() : excluded;
    }

    /** Some SURVIVED or NO_COVERAGE mutant that is not excluded, tried or not. */
    public boolean hasTargets(MutationResult res) {
        return res.mutants.stream().anyMatch(m -> isTargetStatus(m) && !excluded.contains(m.id));
    }

    /** Targets with mutants tried fewer than maxAttempts times. */
    public List<Target> candidates(MutationResult res) {
        List<Target> t = new ArrayList<>(survived(res));
        t.addAll(blocks(res));
        return t;
    }

    /** Picks up to k candidates, alternating SURVIVED and blocks, and counts an attempt for each of their mutants. */
    public List<Target> select(MutationResult res) {
        Deque<Target> s = new ArrayDeque<>(survived(res));
        Deque<Target> b = new ArrayDeque<>(blocks(res));
        List<Target> chosen = new ArrayList<>();
        while (chosen.size() < k && !(s.isEmpty() && b.isEmpty())) {
            Deque<Target> next = chosen.size() % 2 == 0 ? (s.isEmpty() ? b : s) : (b.isEmpty() ? s : b);
            chosen.add(next.poll());
        }
        for (Target t : chosen) {
            for (Mutant m : t.mutants) {
                attempts.merge(m.id, 1, Integer::sum);
            }
        }
        return chosen;
    }

    public Map<String, Integer> getAttempts() {
        return attempts;
    }

    private boolean isTargetStatus(Mutant m) {
        return SURVIVED.equals(m.status) || "NO_COVERAGE".equals(m.status);
    }

    private boolean open(Mutant m) {
        return !excluded.contains(m.id) && attempts.getOrDefault(m.id, 0) < maxAttempts;
    }

    private int tries(Mutant m) {
        return attempts.getOrDefault(m.id, 0);
    }

    /** SURVIVED candidates, fewest attempts first, then by line. */
    private List<Target> survived(MutationResult res) {
        return res.mutants.stream()
                .filter(m -> SURVIVED.equals(m.status) && open(m))
                .sorted(Comparator.comparingInt(this::tries).thenComparingInt(m -> m.line).thenComparing(m -> m.id))
                .map(m -> new Target(SURVIVED, Collections.singletonList(m)))
                .collect(Collectors.toList());
    }

    /**
     * Blocks of NO_COVERAGE candidates: the lines of the focal method that have mutants, in order; a block is a run of
     * lines with candidate NO_COVERAGE mutants, cut by any line with a covered mutant (lines without mutants, or with
     * only excluded or exhausted NO_COVERAGE mutants, do not cut). Fewest attempts first, then by line.
     */
    private List<Target> blocks(MutationResult res) {
        TreeMap<Integer, List<Mutant>> byLine = new TreeMap<>();
        TreeSet<Integer> covered = new TreeSet<>();
        for (Mutant m : res.mutants) {
            if (!"NO_COVERAGE".equals(m.status)) {
                covered.add(m.line);
            } else if (open(m)) {
                byLine.computeIfAbsent(m.line, l -> new ArrayList<>()).add(m);
            }
        }
        List<Target> blocks = new ArrayList<>();
        List<Mutant> cur = new ArrayList<>();
        Integer last = null;
        for (Map.Entry<Integer, List<Mutant>> e : byLine.entrySet()) {
            if (last != null && !covered.subSet(last, false, e.getKey(), false).isEmpty()) {
                blocks.add(new Target(NC_BLOCK, cur));
                cur = new ArrayList<>();
            }
            cur.addAll(e.getValue());
            last = e.getKey();
        }
        if (!cur.isEmpty()) {
            blocks.add(new Target(NC_BLOCK, cur));
        }
        blocks.sort(Comparator.comparingInt((Target t) -> t.mutants.stream().mapToInt(this::tries).min().orElse(0))
                .thenComparingInt(t -> t.from));
        return blocks;
    }

    /** "- line N: `code` -> description (MUTATOR)", as scripts/improve_once.py in CUTMUT (S05). */
    public static String format(Mutant m, List<String> sourceLines) {
        return String.format("- line %d: `%s` -> %s (%s)", m.line, code(m.line, sourceLines), m.description, m.mutatorShort);
    }

    /** The SURVIVED targets, one per line. */
    public static String formatSurvived(List<Target> targets, List<String> sourceLines) {
        return targets.stream().filter(t -> SURVIVED.equals(t.type))
                .map(t -> format(t.mutants.get(0), sourceLines))
                .collect(Collectors.joining("\n"));
    }

    /**
     * The blocks: "- lines a-b are not executed by any test (n mutants), for example: line N: `code` -> description
     * (MUTATOR)", with up to BLOCK_EXAMPLES mutants of the block (the first lines, different mutators if possible).
     */
    public static String formatBlocks(List<Target> targets, List<String> sourceLines) {
        StringBuilder sb = new StringBuilder();
        for (Target t : targets) {
            if (!NC_BLOCK.equals(t.type)) {
                continue;
            }
            List<Mutant> ex = examples(t.mutants);
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(String.format("- %s %s %s not executed by any test (%d mutant%s), for example:",
                    t.from == t.to ? "line" : "lines", t.lines(), t.from == t.to ? "is" : "are",
                    t.mutants.size(), t.mutants.size() == 1 ? "" : "s"));
            for (Mutant m : ex) {
                sb.append(String.format("\n  - line %d: `%s` -> %s (%s)", m.line, code(m.line, sourceLines),
                        m.description, m.mutatorShort));
            }
        }
        return sb.toString();
    }

    static List<Mutant> examples(List<Mutant> mutants) {
        List<Mutant> sorted = mutants.stream().sorted(Comparator.comparingInt((Mutant m) -> m.line).thenComparing(m -> m.id))
                .collect(Collectors.toList());
        List<Mutant> ex = new ArrayList<>();
        Set<String> ops = new HashSet<>();
        for (Mutant m : sorted) {
            if (ex.size() < BLOCK_EXAMPLES && ops.add(m.mutatorShort)) {
                ex.add(m);
            }
        }
        for (Mutant m : sorted) {
            if (ex.size() < BLOCK_EXAMPLES && !ex.contains(m)) {
                ex.add(m);
            }
        }
        ex.sort(Comparator.comparingInt(m -> m.line));
        return ex;
    }

    static String code(int line, List<String> sourceLines) {
        return sourceLines != null && line > 0 && line <= sourceLines.size() ? sourceLines.get(line - 1).trim() : "";
    }

    /** Counts of the focal method without the excluded mutants: mutants, killed (detected), survived, noCoverage, ms, ts. */
    public static Map<String, String> countsWithout(MutationResult res, Set<String> excluded) {
        int total = 0, detected = 0, survived = 0, noCoverage = 0;
        for (Mutant m : res.mutants) {
            if (excluded.contains(m.id)) {
                continue;
            }
            total++;
            if (m.detected) {
                detected++;
            } else if (SURVIVED.equals(m.status)) {
                survived++;
            } else if ("NO_COVERAGE".equals(m.status)) {
                noCoverage++;
            }
        }
        Map<String, String> c = new LinkedHashMap<>();
        c.put("mutants", String.valueOf(total));
        c.put("killed", String.valueOf(detected));
        c.put("survived", String.valueOf(survived));
        c.put("noCoverage", String.valueOf(noCoverage));
        c.put("ms", total == 0 ? "null" : String.valueOf((double) detected / total));
        c.put("ts", detected + survived == 0 ? "null" : String.valueOf((double) detected / (detected + survived)));
        return c;
    }

    /**
     * Score sentence of the improvement prompt, as scripts/improve_once.py in CUTMUT (S05), without the excluded
     * mutants.
     */
    public static String score(MutationResult res, Set<String> excluded) {
        Map<String, String> c = countsWithout(res, excluded);
        int total = Integer.parseInt(c.get("mutants")), det = Integer.parseInt(c.get("killed"));
        int surv = Integer.parseInt(c.get("survived")), nc = Integer.parseInt(c.get("noCoverage"));
        double ms = total == 0 ? 0.0 : (double) det / total;
        double ts = det + surv == 0 ? 0.0 : (double) det / (det + surv);
        return String.format(Locale.ROOT, "%d of its %d mutants are detected (mutation score %.2f, %d mutants are not "
                        + "covered by any test), and %d of the %d covered mutants are detected (test strength %.2f).",
                det, total, ms, nc, det, det + surv, ts);
    }
}
