package zju.cst.aces.coverage;

import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.instr.Instrumenter;

import javax.tools.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class CodeCoverageAnalyzerSofia extends CodeCoverageAnalyzer {

    private byte[] compileAndInstrument(String sourceCode, String className, Instrumenter instr, String targetClassCompiledDir, List<String> dependencies) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);

        File outputDir = new File("target/classes");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        List<File> classPath = new ArrayList<>();
        classPath.add(new File(targetClassCompiledDir));
        for (String dependency : dependencies) {
            classPath.add(new File(dependency));
        }
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Arrays.asList(outputDir));
        fileManager.setLocation(StandardLocation.CLASS_PATH, classPath);

        JavaFileObject file = new JavaSourceFromString(className, sourceCode);
        Iterable<? extends JavaFileObject> compilationUnits =Arrays.asList(file);
        compiler.getTask(null, fileManager, null, null, null, compilationUnits).call();

        File compiledFile = new File(outputDir, className.replace('.', '/') + ".class");
        InputStream compiledClass = new FileInputStream(compiledFile);
        byte[] instrumentedClass = instr.instrument(compiledClass, className);
        compiledClass.close();

        return instrumentedClass;
    }

}
