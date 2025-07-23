package zju.cst.aces.runner.solution_runner;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import zju.cst.aces.api.Logger;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.dto.ClassInfo;
import zju.cst.aces.dto.MethodInfo;
import zju.cst.aces.dto.PromptInfo;
import zju.cst.aces.runner.MethodRunner;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

public class ECRRunner extends MethodRunner {

    private static List<String> dependencies;
    private static Logger logger;

    public ECRRunner(Config config, String fullClassName, MethodInfo methodInfo) throws IOException {
        super(config, fullClassName, methodInfo);
        dependencies = new ArrayList<>(config.getDependencyPaths());

        logger = config.getLogger();
    }

    /*
    Just in case the constructor is not invoked before 'generatePromptInfoWithDep'
     */
    public static void setStaticParams(Config config) {
        dependencies = new ArrayList<>(config.getDependencyPaths());
    }

    public static PromptInfo generatePromptInfoWithDep(Config config, ClassInfo classInfo, MethodInfo methodInfo) throws IOException {
        PromptInfo promptInfo = new PromptInfo(
                true,
                classInfo.fullClassName,
                methodInfo.methodName,
                methodInfo.methodSignature,
                methodInfo.methodDescriptor);
        promptInfo.setClassInfo(classInfo);
        promptInfo.setMethodInfo(methodInfo);
        List<String> otherBriefMethods = new ArrayList<>();
        List<String> otherMethodBodies = new ArrayList<>();

        for (Map.Entry<String, Set<String>> entry : classInfo.constructorDeps.entrySet()) {
            String depClassName = entry.getKey();
            Set<String> depMethods = entry.getValue();
            if (methodInfo.dependentMethods.containsKey(depClassName)) {
                continue;
            }

            promptInfo.addConstructorDeps(depClassName, getDepInfo(config, depClassName, depMethods));
            promptInfo.addExternalConstructorDeps(depClassName, ECRRunner.getDepInfo(config, depClassName, depMethods, promptInfo));
        }

        for (Map.Entry<String, Set<String>> entry : methodInfo.dependentMethods.entrySet()) {
            String depClassName = entry.getKey();
            if (depClassName.equals(classInfo.getClassName())) {
                Set<String> otherSig = methodInfo.dependentMethods.get(depClassName);
                for (String otherMethod : otherSig) {
                    MethodInfo otherMethodInfo = getMethodInfo(config, classInfo, otherMethod);
                    if (otherMethodInfo == null) {
                        continue;
                    }
                    // only add the methods in focal class that are invoked
                    otherBriefMethods.add(otherMethodInfo.brief);
                    otherMethodBodies.add(otherMethodInfo.sourceCode);
                }
                continue;
            }

            Set<String> depMethods = entry.getValue();
            promptInfo.addMethodDeps(depClassName, getDepInfo(config, depClassName, depMethods));
            promptInfo.addExternalMethodDeps(depClassName, ECRRunner.getDepInfo(config, depClassName, depMethods, promptInfo));
            addMethodDepsByDepth(config, depClassName, depMethods, promptInfo, config.getDependencyDepth());
        }

        String fields = joinLines(classInfo.fields);
        String imports = joinLines(classInfo.imports);

        String information = classInfo.packageName
                + "\n" + imports
                + "\n" + classInfo.classSignature
                + " {\n";
        //TODO: handle used fields instead of all fields
        String otherMethods = "";
        String otherFullMethods = "";
        if (classInfo.hasConstructor) {
            otherMethods += joinLines(classInfo.constructorBrief) + "\n";
            otherFullMethods += getBodies(config, classInfo, classInfo.constructorSigs) + "\n";
        }
//        if (methodInfo.useField) {
//            information += fields + "\n";
//            otherMethods +=  joinLines(classInfo.getterSetterBrief) + "\n";
//            otherFullMethods += getBodies(config, classInfo, classInfo.getterSetterSigs) + "\n";
//        }
        information += fields + "\n";
        otherMethods +=  joinLines(classInfo.getterSetterBrief) + "\n";
        otherFullMethods += getBodies(config, classInfo, classInfo.getterSetterSigs) + "\n";

        otherMethods += joinLines(otherBriefMethods) + "\n";
        otherFullMethods += joinLines(otherMethodBodies) + "\n";
        information += methodInfo.sourceCode + "\n}";

        promptInfo.setContext(information);
        promptInfo.setOtherMethodBrief(otherMethods);
        promptInfo.setOtherMethodBodies(otherFullMethods);
        return promptInfo;
    }

    public static String getDepInfo(Config config, String depClassName, Set<String> depMethods, PromptInfo promptInfo) throws IOException {
        ClassInfo depClassInfo = getClassInfo(config, depClassName);
        if (depClassInfo == null) {
            try {
                String sourceCode = getSourceCode(depClassName, depMethods);
                if (sourceCode != null) {
                    promptInfo.incrementSofiaActivations();
                }
                return sourceCode;
            } catch (Exception e) {
                return null;
            }
        } else {
            return null;
        }
    }

    public static String getSourceCode(String className, Set<String> depMethods) {
        String classPath = className.replace('.', '/') + ".class";
        for (String dependency : dependencies) {
            try {
                File jarFile = new File(dependency);
                if (jarFile.exists()) {
                    String decompiledClass = decompileClassFromJar(jarFile, classPath);
                    if (decompiledClass != null) {
                        return cleanSourceCode(decompiledClass, depMethods);
                    }
                }
            } catch(Exception e) {
                continue;
            }
        }
        return null;
    }

    private static String decompileClassFromJar(File jarFile, String classPath) throws IOException {
        // Extract .class from JAR
        File tempClassFile = extractClassFile(jarFile, classPath);
        if (tempClassFile == null) {
            return null;
        }

        // Use CFR decompiler
        StringWriter writer = new StringWriter();

        OutputSinkFactory mySink = new OutputSinkFactory() {
            @Override
            public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
                return Arrays.asList(SinkClass.STRING);
            }

            @Override
            public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
                return message -> writer.write(message.toString() + "\n");
            }
        };

        // CFR options for safer decompilation
        Map<String, String> options = new HashMap<String, String>();
        options.put("recover", "true");
        options.put("hideutf", "true");
        options.put("decodesignatures", "false");
        options.put("comments", "false");


        CfrDriver driver = new CfrDriver.Builder()
                .withOptions(options)
                .withOutputSink(mySink)
                .build();

        driver.analyse(Arrays.asList(tempClassFile.getAbsolutePath()));

        return writer.toString();
    }


    private static File extractClassFile(File jarFile, String classPath) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry(classPath);
            if (entry == null) {
                return null;
            }

            File tempFile = Files.createTempFile("class", ".class").toFile();
            try (InputStream in = jar.getInputStream(entry);
                 OutputStream out = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            return tempFile;
        }
    }

    /*
        Extracts dependent methods source code and public variables and methods signatures from the given source code.
     */
    public static String cleanSourceCode(String source, Set<String> depMethods) {
        ParserConfiguration config = new ParserConfiguration()
                .setAttributeComments(false) // ignora comentarios mal ubicados
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8)
                .setDoNotAssignCommentsPrecedingEmptyLines(true)
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                .setStoreTokens(false); // no guarda tokens, consume menos memoria
        JavaParser parser = new JavaParser(config);
        String sanitizedSource = sanitizeForJavaParser(source);
        String sourceWithoutJavadoc = removeLeadingJavadoc(sanitizedSource);
        CompilationUnit cu = parser.parse(sourceWithoutJavadoc).getResult().orElseThrow(() -> new RuntimeException("Parsing failed"));

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            // Remove private fields
            clazz.getMembers().removeIf(member ->
                    member instanceof com.github.javaparser.ast.body.FieldDeclaration &&
                            ((com.github.javaparser.ast.body.FieldDeclaration) member).isPrivate()
            );

            List<MethodDeclaration> methodsToKeep = clazz.getMethods().stream()
                    .filter(method -> {
                        String name = method.getNameAsString();
                        List<String> paramTypes = method.getParameters().stream()
                                .map(p -> p.getType().asString().replaceAll("\\s+", ""))
                                .collect(Collectors.toList());
                        String fullSignature = name + "(" + String.join(", ", paramTypes) + ")";
                        return depMethods.contains(fullSignature);
                    })
                    .collect(Collectors.toList());

            // Collect signatures of methodsToKeep for comparison
            Set<String> keptSignatures = methodsToKeep.stream()
                    .map(method -> {
                        String name = method.getNameAsString();
                        List<String> paramTypes = method.getParameters().stream()
                                .map(p -> p.getType().asString().replaceAll("\\s+", ""))
                                .collect(Collectors.toList());
                        return name + "(" + String.join(", ", paramTypes) + ")";
                    })
                    .collect(Collectors.toSet());

            // Find all public methods not in methodsToKeep
            List<MethodDeclaration> publicSignatures = clazz.getMethods().stream()
                    .filter(method -> method.isPublic())
                    .filter(method -> {
                        String name = method.getNameAsString();
                        List<String> paramTypes = method.getParameters().stream()
                                .map(p -> p.getType().asString().replaceAll("\\s+", ""))
                                .collect(Collectors.toList());
                        String fullSignature = name + "(" + String.join(", ", paramTypes) + ")";
                        return !keptSignatures.contains(fullSignature);
                    })
                    .map(method -> {
                        MethodDeclaration sig = method.clone();
                        sig.removeBody();
                        return sig;
                    })
                    .collect(Collectors.toList());

            // Remove all methods
            clazz.getMembers().removeIf(member -> member instanceof MethodDeclaration);

            // Add only the matched method(s)
            clazz.getMembers().addAll(methodsToKeep);

            // Add public method signatures not already present
            clazz.getMembers().addAll(publicSignatures);
        });

        return cu.toString();
    }

    /**
     * Preprocesses source code to help JavaParser handle synthetic class references and misplaced comments.
     * - Replaces synthetic class references (e.g., Foo$1) with valid identifiers or null.
     * - Removes or replaces comments between expressions that break parsing.
     */
    public static String sanitizeForJavaParser(String source) {
        // Replace synthetic class references (e.g., Foo$1) with a valid identifier or null
        // Only replace when used as a type, not as an identifier (avoid replacing method names)
        // Replace occurrences like "Foo$1 " or "Foo$1," or "Foo$1)" but NOT "Foo$1(" (method name)
        source = source.replaceAll("(?<=[\\s\\(,])([a-zA-Z_][a-zA-Z0-9_]*\\$\\d+)(?=[\\s,;\\)])", "null");

        // Remove comments between expressions (inline block comments that break parsing)
        // e.g., "foo(/* comment */bar)" => "foo(bar)"
        source = source.replaceAll("/\\*.*?\\*/", " ");

        // Remove line comments that are not at the start of a line
        source = source.replaceAll("([^\\n])//.*", "$1");

        // Optionally, remove any remaining problematic comments
        // source = source.replaceAll("//.*", "");

        return source;
    }

    public static String removeLeadingJavadoc(String source) {
        return source.replaceFirst("(?s)^Analysing.*?\\R*/\\*.*?\\*/\\s*", "");
    }


}


