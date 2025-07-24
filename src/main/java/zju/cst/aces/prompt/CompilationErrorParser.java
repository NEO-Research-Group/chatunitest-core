package zju.cst.aces.prompt;

import zju.cst.aces.runner.solution_runner.SofiaRunner;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class CompilationErrorParser {

    public static Set<String> extractClassNamesFromErrors(String errorLog) {
        Set<String> classNames = new HashSet<>();

        // Patterns for each entity type
        Pattern classPattern = Pattern.compile("location: class ([\\w.$]+)");
        Pattern variablePattern = Pattern.compile("location: variable \\w+ of type ([\\w.$]+)");
        Pattern methodPattern = Pattern.compile("location: method \\w+\\([^)]*\\) of class ([\\w.$]+)");
        Pattern constructorPattern = Pattern.compile("location: constructor ([\\w.$]+)\\([^)]*\\)");
        Pattern typeParamPattern = Pattern.compile("location: type parameter \\w+ of class ([\\w.$]+)");
        Pattern annotationElementPattern = Pattern.compile("location: annotation element \\w+ of annotation type ([\\w.$]+)");
        // Note: We ignore "location: package ..." since it's not a class

        Matcher[] matchers = new Matcher[] {
                classPattern.matcher(errorLog),
                variablePattern.matcher(errorLog),
                methodPattern.matcher(errorLog),
                constructorPattern.matcher(errorLog),
                typeParamPattern.matcher(errorLog),
                annotationElementPattern.matcher(errorLog)
        };

        for (Matcher matcher : matchers) {
            while (matcher.find()) {
                classNames.add(matcher.group(1));
            }
        }

        return classNames;
    }

    public static void main(String[] args) {
        String errorLog =
                  "Error in ExceptionUtils_meterJsonProcessingException_2_0_Test: line 27 : cannot find symbol\n" +
                  "  symbol:   method setRegistry(com.codahale.metrics.MetricRegistry)\n" +
                  "  location: enum com.apple.spark.util.MarkerUtils\n" +
                  "Error in ExceptionUtils_meterJsonProcessingException_2_0_Test: line 29 : cannot find symbol\n" +
                  "  symbol:   method setRuntimeExceptionMeter(com.codahale.metrics.Meter)\n" +
                  "  location: class com.apple.spark.util.ExceptionUtils<String>\n";

        Set<String> classes = extractClassNamesFromErrors(errorLog);
        System.out.println("Classes found:");
        classes.forEach(System.out::println);
        for (String className : classes) {
            System.out.println(SofiaRunner.getSourceCode(className, null));
        }
    }
}
