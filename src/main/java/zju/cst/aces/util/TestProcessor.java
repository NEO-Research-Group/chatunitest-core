package zju.cst.aces.util;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import zju.cst.aces.dto.PromptInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @ClassName TestProcessor
 * @Description Process generated tests, remove the error test case in the test class
 */

//TODO: remove correct test case in the repair prompt.
public class TestProcessor {
    private static final JavaParser parser = new JavaParser();
    private String fullTestName;

    public TestProcessor(String fullTestName) {
        this.fullTestName = fullTestName;
    }

    public List<Integer> getErrorLineNum(TestExecutionSummary summary) {
        List<Integer> errorLineNum = new ArrayList<>();
        summary.getFailures().forEach(failure -> {
            for (StackTraceElement st : failure.getException().getStackTrace()) {
                if (st.getClassName().contains(fullTestName)) {
                    int lineNum = st.getLineNumber();
                    errorLineNum.add(lineNum);
                }
            }
        });
        return errorLineNum;
    }

    public boolean containError(List<Integer> errorLineNum, MethodDeclaration method) {
        int beginPosition = method.getBegin().get().line;
        int endPosition = method.getEnd().get().line;
        return !errorLineNum.stream().filter(lineNum -> lineNum >= beginPosition && lineNum <= endPosition)
                .collect(Collectors.toList()).isEmpty();
    }

    public boolean isTestCase(MethodDeclaration method) {
        return !method.getAnnotations().stream().filter(annotationExpr -> {
            String annotationName = annotationExpr.getNameAsString();
            return annotationName.equals("Test") || annotationName.equals("ParameterizedTest");
        }).collect(Collectors.toList()).isEmpty();
    }

    // Literals (kept as they are), comments and whitespace: as TOKEN_RE of scripts/eval_cycles.py in CUTMUT
    static final Pattern TOKEN = Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')|//[^\\n]*|/\\*.*?\\*/|\\s+",
            Pattern.DOTALL);

    /** A test body without comments and whitespace outside the literals ("a" and " a" are not the same test). */
    public static String normBody(String body) {
        Matcher m = TOKEN.matcher(body);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, m.group(1) == null ? "" : Matcher.quoteReplacement(m.group(1)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Name -> normalized body (normBody of the original text of the body, from its '{') of each @Test method, as
     * norm_body of scripts/eval_cycles.py in CUTMUT; null if the code cannot be parsed.
     */
    public Map<String, String> testBodies(String code) {
        try {
            CompilationUnit cu = parser.parse(code).getResult()
                    .orElseThrow(() -> new NoSuchElementException("CompilationUnit not present in parse result"));
            Map<String, String> bodies = new LinkedHashMap<>();
            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                if (isTestCase(method) && method.getBody().isPresent() && method.getBody().get().getTokenRange().isPresent()) {
                    bodies.put(method.getNameAsString(), normBody(method.getBody().get().getTokenRange().get().toString()));
                }
            }
            return bodies;
        } catch (Exception e) {
            return null;
        }
    }

    /** Number of @Test/@ParameterizedTest methods in the given test code, or -1 if it cannot be parsed. */
    public int countTestCases(String code) {
        try {
            ParseResult<CompilationUnit> parseResult = parser.parse(code);
            CompilationUnit cu = parseResult.getResult().orElseThrow(()->new NoSuchElementException("CompilationUnit not present in parse result"));
            return (int) cu.findAll(MethodDeclaration.class).stream().filter(this::isTestCase).count();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Assertions in the @Test/@ParameterizedTest methods, as scripts/count_tests.py in CUTMUT: calls to assert*
     * (assertEquals, assertThrows, assertThat...), verify and fail, also qualified (Assertions.assertEquals,
     * Mockito.verify). Comments are not counted. Returns -1 if the code cannot be parsed.
     */
    public int countAssertions(String code) {
        try {
            ParseResult<CompilationUnit> parseResult = parser.parse(code);
            CompilationUnit cu = parseResult.getResult().orElseThrow(()->new NoSuchElementException("CompilationUnit not present in parse result"));
            return (int) cu.findAll(MethodDeclaration.class).stream().filter(this::isTestCase)
                    .flatMap(m -> m.findAll(MethodCallExpr.class).stream())
                    .map(MethodCallExpr::getNameAsString)
                    .filter(n -> n.startsWith("assert") || n.equals("verify") || n.equals("fail"))
                    .count();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Names of the test methods that failed, taken from the JUnit test identifiers (not from line numbers).
     * Returns null if some failure is not attributed to a test method (e.g. a failure of the test class).
     */
    public Set<String> getFailedTestNames(TestExecutionSummary summary) {
        Set<String> names = new HashSet<>();
        for (TestExecutionSummary.Failure failure : summary.getFailures()) {
            if (!failure.getTestIdentifier().getSource().filter(s -> s instanceof MethodSource).isPresent()) {
                return null;
            }
            names.add(((MethodSource) failure.getTestIdentifier().getSource().get()).getMethodName());
        }
        return names;
    }

    /**
     * Removes only the failed @Test methods (by name) and keeps the rest of the class (fields, set-up, helpers).
     * Returns null if the failures cannot be attributed to test methods or no test case is left.
     */
    public String keepPassingTests(String code, TestExecutionSummary summary) {
        Set<String> failed = getFailedTestNames(summary);
        if (failed == null) {
            return null;
        }
        return removeTestsByName(code, failed);
    }

    /**
     * Removes the @Test methods with these names and keeps the rest of the class (fields, set-up, helpers).
     * The names may come from JUnit (getFailedTestNames) or from PIT (MutationResult.getFailingTests).
     * Returns null if the code cannot be parsed or no test case is left.
     */
    public String removeTestsByName(String code, Set<String> names) {
        try {
            CompilationUnit cu = parser.parse(code).getResult()
                    .orElseThrow(() -> new NoSuchElementException("CompilationUnit not present in parse result"));
            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                if (isTestCase(method) && names.contains(method.getNameAsString())) {
                    method.remove();
                }
            }
            if (cu.findAll(MethodDeclaration.class).stream().noneMatch(this::isTestCase)) {
                return null;
            }
            return cu.toString();
        } catch (Exception e) {
            System.out.println("In TestProcessor.removeTestsByName: " + e);
            return null;
        }
    }

    public String removeErrorTest(PromptInfo promptInfo, TestExecutionSummary summary) {
        String result = promptInfo.getUnitTest();
        try {
            ParseResult<CompilationUnit> parseResult = parser.parse(result);
            CompilationUnit cu = parseResult.getResult().orElseThrow(()->new NoSuchElementException("CompilationUnit not present in parse result"));
            List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
            Set<String> failed = getFailedTestNames(summary);
            List<Integer> errorLineNum = getErrorLineNum(summary);
            for (MethodDeclaration method : methods) {
                // By test name when every failure belongs to a test method: only those @Test are removed, never the
                // set-up or helpers (e.g. Mockito's UnnecessaryStubbingException points to the stubbing in setUp).
                // Otherwise, the original criterion: any method with a line in the stack traces.
                if (failed != null ? isTestCase(method) && failed.contains(method.getNameAsString())
                        : containError(errorLineNum, method)) {
                    method.remove();
                }
            }
            if (cu.findAll(MethodDeclaration.class).stream().filter(this::isTestCase).collect(Collectors.toList()).isEmpty()) {
                return null;
            }
            result = cu.toString();
        } catch (Exception e) {
            System.out.println("In TestProcessor.removeErrorTest: " + e);
            return null;
        }
        return result;
    }

    public String removeCorrectTest(PromptInfo promptInfo, TestExecutionSummary summary) {
        String result = promptInfo.getUnitTest();
        try {
            ParseResult<CompilationUnit> parseResult = parser.parse(result);
            CompilationUnit cu = parseResult.getResult().orElseThrow(()->new NoSuchElementException("CompilationUnit not present in parse result"));
            List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
            Set<String> failed = getFailedTestNames(summary);
            List<Integer> errorLineNum = getErrorLineNum(summary);
            for (MethodDeclaration method : methods) {
                boolean hasError = failed != null ? failed.contains(method.getNameAsString()) : containError(errorLineNum, method);
                if (!hasError && isTestCase(method)) {
                    promptInfo.addCorrectTest(method);
                    method.remove();
                }
            }
            result = cu.toString();
            if (cu.findAll(MethodDeclaration.class).stream().filter(this::isTestCase).collect(Collectors.toList()).isEmpty()) {
//                throw new Exception("In TestProcessor.removeCorrectTest: No test case left");
                System.out.println("In TestProcessor.removeCorrectTest: No test case left");
            }
        } catch (Exception e) {
            System.out.println("In TestProcessor.removeCorrectTest: " + e);
        }
        promptInfo.setUnitTest(result);
        return result;
    }

    public String addCorrectTest(PromptInfo promptInfo) {
        String result = promptInfo.getUnitTest();
        try {
            ParseResult<CompilationUnit> parseResult = parser.parse(result);
            CompilationUnit cu = parseResult.getResult().orElseThrow(()->new NoSuchElementException("CompilationUnit not present in parse result"));
            promptInfo.getCorrectTests().keySet().forEach(className -> {
                cu.getClassByName(className).ifPresent(classOrInterfaceDeclaration -> {
                    promptInfo.getCorrectTests().get(className).forEach(methodDeclaration -> {
                        if (classOrInterfaceDeclaration.getMethodsByName(methodDeclaration.getNameAsString()).isEmpty()) {
                            classOrInterfaceDeclaration.addMember(methodDeclaration);
                        }
                    });
                });
            });
            promptInfo.getCorrectTests().clear();
            result = cu.toString();
        } catch (Exception e) {
            System.out.println("In TestProcessor.addCorrectTest: " + e);
        }
        promptInfo.setUnitTest(result);
        return result;
    }
}
