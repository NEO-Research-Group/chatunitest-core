package zju.cst.aces.mutation;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Targets of the improvement prompt (enfoque.md §3.2, E2): each line of the focal method with SURVIVED mutants (all
 * of them, as one target), and each block of contiguous lines whose mutants are NO_COVERAGE, minus the excluded
 * mutants (e.g. the equivalent ones). At most k per prompt, SURVIVED lines and blocks alternated (S, B, S, B...), the
 * least tried first. Each appearance in a prompt is an attempt for every mutant of the target, also if the cycle is
 * discarded; a mutant tried maxAttempts times without being killed is no longer a target. The attempts are kept per
 * mutant (Mutant.id is stable), so a target can shrink between cycles. One selector per arm: the generic arm selects
 * in the same way without showing the targets, so that both arms stop by the same rule (no target left).
 */
public class MutantSelector {
    public static final String SURVIVED = "SURVIVED";
    public static final String NC_BLOCK = "NC_BLOCK";
    static final int BLOCK_EXAMPLES = 2;
    static final int MAX_TEST_NAMES = 5;
    public static final String HINT_VERIFY = "VERIFY";
    public static final String HINT_RETURN = "RETURN";
    static final List<String> CALL_MUTATORS = Arrays.asList("VoidMethodCallMutator", "NonVoidMethodCallMutator",
            "ConstructorCallMutator", "ArgumentPropagationMutator", "NakedReceiverMutator");
    static final Pattern OWNER = Pattern.compile("([\\w/$]+)::");

    /** A line with SURVIVED mutants, or a block of lines from-to that no test executes (its NO_COVERAGE mutants). */
    public static class Target {
        public final String type;
        public final List<Mutant> mutants;
        public final int from;
        public final int to;
        public String label;                                  // L1, L2... / B1, B2... in the prompt (mutants arm)
        public String hint;                                   // VERIFY if some change of the line is a call on a mock
        public List<Integer> triedInCycles = new ArrayList<>(); // earlier cycles in which some of its mutants was shown

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

        /** For the JSON report: type, lines, mutant ids and, in the mutants arm, label, hint and earlier cycles. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("lines", lines());
            m.put("mutants", ids());
            if (label != null) {
                m.put("label", label);
            }
            if (hint != null) {
                m.put("hint", hint);
            }
            if (!triedInCycles.isEmpty()) {
                m.put("triedInCycles", triedInCycles);
            }
            return m;
        }
    }

    /** The parts of the improvement prompt of the mutants arm (render). */
    public static class Rendered {
        public String markedMethod = "";   // source of the focal method with the targets marked; "" if not found
        public String survived = "";
        public String blocks = "";
    }

    private final int k;
    private final int maxAttempts;
    private final Set<String> excluded;
    private final Map<String, Integer> attempts = new LinkedHashMap<>();
    private final Map<String, List<Integer>> shownIn = new HashMap<>();

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

    /**
     * Picks up to k candidates for cycle, alternating SURVIVED lines and blocks, records the earlier cycles in which
     * their mutants were shown and counts an attempt for each of their mutants.
     */
    public List<Target> select(MutationResult res, int cycle) {
        Deque<Target> s = new ArrayDeque<>(survived(res));
        Deque<Target> b = new ArrayDeque<>(blocks(res));
        List<Target> chosen = new ArrayList<>();
        while (chosen.size() < k && !(s.isEmpty() && b.isEmpty())) {
            Deque<Target> next = chosen.size() % 2 == 0 ? (s.isEmpty() ? b : s) : (b.isEmpty() ? s : b);
            chosen.add(next.poll());
        }
        for (Target t : chosen) {
            TreeSet<Integer> before = new TreeSet<>();
            for (Mutant m : t.mutants) {
                before.addAll(shownIn.getOrDefault(m.id, Collections.emptyList()));
                attempts.merge(m.id, 1, Integer::sum);
                shownIn.computeIfAbsent(m.id, x -> new ArrayList<>()).add(cycle);
            }
            t.triedInCycles = new ArrayList<>(before);
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

    private int minTries(Target t) {
        return t.mutants.stream().mapToInt(this::tries).min().orElse(0);
    }

    /** One target per line with SURVIVED candidates, fewest attempts first, then by line. */
    private List<Target> survived(MutationResult res) {
        TreeMap<Integer, List<Mutant>> byLine = new TreeMap<>();
        res.mutants.stream().filter(m -> SURVIVED.equals(m.status) && open(m))
                .sorted(Comparator.comparing((Mutant m) -> m.id))
                .forEach(m -> byLine.computeIfAbsent(m.line, l -> new ArrayList<>()).add(m));
        return byLine.values().stream().map(ms -> new Target(SURVIVED, ms))
                .sorted(Comparator.comparingInt(this::minTries).thenComparingInt(t -> t.from))
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
        blocks.sort(Comparator.comparingInt(this::minTries).thenComparingInt(t -> t.from));
        return blocks;
    }

    /**
     * The mutants part of the improvement prompt (E2). The LLM does not see line numbers, so the targets are labelled
     * (L1, L2... for the lines with SURVIVED mutants, B1, B2... for the blocks) and marked with "// <-- [label]" in a
     * copy of the focal method (lines methodBegin-methodEnd of the source file, 1-based; none if methodBegin <= 0).
     * Each SURVIVED line lists its changes with what a test needs to detect each one (need()), the tests that execute
     * it and still pass, and the earlier cycles in which it was tried. Sets label and hint of each target.
     */
    public static Rendered render(List<Target> targets, List<String> sourceLines, int methodBegin, int methodEnd,
                                  String suiteCode) {
        Rendered r = new Rendered();
        Map<Integer, List<String>> marks = new TreeMap<>();
        int nl = 0, nb = 0;
        StringBuilder s = new StringBuilder(), b = new StringBuilder();
        for (Target t : targets) {
            if (SURVIVED.equals(t.type)) {
                t.label = "L" + (++nl);
                marks.computeIfAbsent(t.from, x -> new ArrayList<>()).add(t.label);
                boolean verify = false;
                if (s.length() > 0) {
                    s.append('\n');
                }
                s.append(String.format("- [%s] `%s`", t.label, code(t.from, sourceLines)));
                for (Mutant m : t.mutants) {
                    boolean mocked = isMockedCall(m, suiteCode);
                    verify |= mocked;
                    s.append(String.format("\n  - change: %s (%s). To detect it: %s", m.description, m.mutatorShort,
                            need(m, mocked, code(m.line, sourceLines))));
                }
                t.hint = verify ? HINT_VERIFY : HINT_RETURN;
                List<String> tests = t.mutants.stream().flatMap(m -> m.succeedingTests.stream()).distinct()
                        .map(MutantSelector::shortTest).collect(Collectors.toList());
                if (!tests.isEmpty()) {
                    s.append("\n  Tests that execute this line and still pass with these changes: ")
                            .append(tests.stream().limit(MAX_TEST_NAMES).collect(Collectors.joining(", ")))
                            .append(tests.size() > MAX_TEST_NAMES ? " and " + (tests.size() - MAX_TEST_NAMES) + " more" : "")
                            .append(". What they check is not enough to tell these changes apart.");
                }
                appendTried(s, t);
            } else {
                t.label = "B" + (++nb);
                for (Mutant m : t.mutants) {
                    List<String> l = marks.computeIfAbsent(m.line, x -> new ArrayList<>());
                    if (!l.contains(t.label)) {
                        l.add(t.label);
                    }
                }
                if (b.length() > 0) {
                    b.append('\n');
                }
                b.append(String.format("- [%s] %s %s: no test executes %s (%d mutant%s), for example:", t.label,
                        t.from == t.to ? "the line" : "the lines", "marked [" + t.label + "]",
                        t.from == t.to ? "it" : "them", t.mutants.size(), t.mutants.size() == 1 ? "" : "s"));
                for (Mutant m : examples(t.mutants)) {
                    b.append(String.format("\n  - `%s` -> %s (%s)", code(m.line, sourceLines), m.description,
                            m.mutatorShort));
                }
                appendTried(b, t);
            }
        }
        r.survived = s.toString();
        r.blocks = b.toString();
        if (sourceLines != null && methodBegin > 0 && methodEnd >= methodBegin && methodEnd <= sourceLines.size()) {
            StringBuilder mm = new StringBuilder();
            for (int line = methodBegin; line <= methodEnd; line++) {
                mm.append(sourceLines.get(line - 1));
                List<String> l = marks.get(line);
                if (l != null) {
                    mm.append("   // <-- ").append(l.stream().map(x -> "[" + x + "]").collect(Collectors.joining(" ")));
                }
                mm.append('\n');
            }
            r.markedMethod = mm.toString();
        }
        return r;
    }

    private static void appendTried(StringBuilder sb, Target t) {
        if (!t.triedInCycles.isEmpty()) {
            sb.append(String.format("\n  Already targeted in cycle%s %s, and the new tests did not detect %s: try a different "
                            + "input or a different assertion.", t.triedInCycles.size() == 1 ? "" : "s",
                    t.triedInCycles.stream().map(String::valueOf).collect(Collectors.joining(", ")),
                    NC_BLOCK.equals(t.type) ? "a change in these lines" : "these changes"));
        }
    }

    /** "pkg.Cls#method" -> "Cls.method" */
    static String shortTest(String name) {
        int h = name.indexOf('#');
        String cls = h < 0 ? name : name.substring(0, h);
        cls = cls.substring(cls.lastIndexOf('.') + 1);
        return h < 0 ? cls : cls + "." + name.substring(h + 1);
    }

    /** What a test needs to detect the mutant, from its mutator (E2: "a hint of what is needed to kill it"). */
    static String need(Mutant m, boolean mocked, String code) {
        String op = m.mutatorShort == null ? "" : m.mutatorShort;
        boolean several = code.contains("&&") || code.contains("||");
        String which = several ? " The line has several conditions; the change affects one of them." : "";
        if (op.startsWith("ConditionalsBoundary")) {
            return "only an input where both sides of the comparison are equal tells it apart (e.g. a size or value "
                    + "exactly at the limit); check the returned value or the effect for that input." + which;
        }
        if (op.startsWith("RemoveConditionalMutator") && op.endsWith("_IF")) {
            return "with the change this branch is always taken. Use an input that makes the condition false and check "
                    + "that the effect of the branch does not happen (returned value, state, or `verify(..., never())`)." + which;
        }
        if (op.startsWith("RemoveConditionalMutator") && op.endsWith("_ELSE")) {
            return "with the change this branch is never taken. Use an input that makes the condition true and check "
                    + "the effect of the branch (returned value, state or `verify`)." + which;
        }
        if (op.startsWith("NegateConditionals")) {
            return "with the change the other branch is taken. Check the result of the branch that the input takes "
                    + "(returned value, state or `verify`)." + which;
        }
        if (op.contains("ReturnVals") || op.startsWith("PrimitiveReturns") || op.startsWith("BooleanTrueReturn")
                || op.startsWith("BooleanFalseReturn")) {
            return "the method returns a different value. Assert the exact returned value, not only that it is not null.";
        }
        if (op.startsWith("Math") || op.startsWith("Increments") || op.startsWith("InvertNegs")) {
            return "a computed value changes. Assert the exact value that results from it.";
        }
        if (op.startsWith("VoidMethodCall")) {
            return mocked ? "the call is removed. `verify` that it happens, with its exact arguments (an `ArgumentCaptor` "
                    + "for the objects built inside the method)."
                    : "the call is removed. Check its effect (state, result or exception).";
        }
        if (op.startsWith("NonVoidMethodCall")) {
            return mocked ? "the call is removed and its result replaced by a default value (null, 0, false). Stub it to "
                    + "return a non-default value and check that the focal method uses it, or `verify` the call."
                    : "the call is removed and its result replaced by a default value (null, 0, false). Use an input where "
                    + "the result of this call matters and check the outcome.";
        }
        if (op.startsWith("ConstructorCall")) {
            return "the new object is replaced by null. Check something that depends on it (the argument it is passed "
                    + "to, captured with an `ArgumentCaptor`, the result or the exception).";
        }
        if (op.startsWith("ArgumentPropagation") || op.startsWith("NakedReceiver")) {
            return "the call is replaced by " + (op.startsWith("NakedReceiver") ? "its receiver" : "one of its arguments")
                    + ". Make the result of the call differ from it (stub it if it is a mock) and check the outcome.";
        }
        return "use an input at the boundary and check the returned value or the exception (type and message).";
    }

    /**
     * A call mutator on a dependency that the suite mocks: the owner of the call comes from PIT's description ("call to
     * a/b/C::m"); it is a mocked dependency if it is not the focal class nor a JDK class (java/, javax/) and the suite
     * has @Mock or @MockBean fields of its type, or mock(C.class) or mockStatic(C.class).
     */
    public static boolean isMockedCall(Mutant m, String suiteCode) {
        if (m.mutatorShort == null || CALL_MUTATORS.stream().noneMatch(m.mutatorShort::startsWith)) {
            return false;
        }
        Matcher om = m.description == null ? null : OWNER.matcher(m.description);
        if (om == null || !om.find()) {
            return false;
        }
        String owner = om.group(1);
        String dotted = owner.replace('/', '.');
        String focal = m.mutatedClass == null ? "" : m.mutatedClass;
        String focalTop = focal.contains("$") ? focal.substring(0, focal.indexOf('$')) : focal;
        if (owner.startsWith("java/") || owner.startsWith("javax/") || dotted.equals(focal)
                || dotted.equals(focalTop) || dotted.startsWith(focalTop + "$")) {
            return false;
        }
        String simple = owner.substring(Math.max(owner.lastIndexOf('/'), owner.lastIndexOf('$')) + 1);
        return isMocked(simple, suiteCode);
    }

    static boolean isMocked(String simpleName, String code) {
        if (code == null || simpleName.isEmpty()) {
            return false;
        }
        String n = Pattern.quote(simpleName);
        Pattern field = Pattern.compile("@(?:Mock|MockBean)\\b(?:\\([^)]*\\))?\\s+(?:(?:private|protected|public|static|final)\\s+)*"
                + "(?:[\\w.]+\\.)?" + n + "\\b");
        Pattern call = Pattern.compile("\\b(?:mock|mockStatic)\\s*\\(\\s*(?:[\\w.]+\\.)?" + n + "\\.class\\b");
        return field.matcher(code).find() || call.matcher(code).find();
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
