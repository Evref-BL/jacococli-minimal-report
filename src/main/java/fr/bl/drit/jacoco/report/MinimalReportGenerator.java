package fr.bl.drit.jacoco.report;

import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ILine;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.analysis.ISourceNode;
import org.jacoco.core.tools.ExecFileLoader;

public class MinimalReportGenerator {

  // ==========================================================================
  // === CLI ==================================================================
  // ==========================================================================

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      printUsage();
      return;
    }

    List<String> execFiles = new ArrayList<>();
    List<String> classFiles = new ArrayList<>();
    String outPath = null;

    // parse arguments
    boolean collectExec = true; // collect exec files until --classfiles
    int i = 0;
    while (i < args.length) {
      String arg = args[i];
      switch (arg) {
        case "--classfiles":
          collectExec = false;
          i++;
          break;
        case "--json":
          if (i + 1 >= args.length) {
            badArgs("\nOption \"--json\" takes an operand");
          }
          outPath = args[i + 1];
          i += 2;
          break;
        case "--help":
          printUsage();
          return;
        default:
          // treat as exec file until --classfiles, then treat as classpath
          if (collectExec) {
            execFiles.add(arg);
          } else {
            classFiles.add(arg);
          }
          i++;
          break;
      }
    }

    if (execFiles.isEmpty()) {
      badArgs("\nAt least one *.exec file is required");
    }

    if (classFiles.isEmpty()) {
      badArgs("\nOption \"--classfiles\" is required and at least one path must be given");
    }

    if (outPath == null) {
      badArgs("\nOption \"--json\" is required");
    }

    // generate the report
    File outFile = writeReport(generateReport(execFiles, classFiles), outPath);

    System.out.println("Report generated: " + outFile.getAbsolutePath());
  }

  private static void printUsage() {
    System.out.println("Generate minimal JSON reports by reading exec and Java class files.");
    System.out.println();
    System.out.println(
        "Usage: java -jar jacococli-minimal-report.jar <execfiles> ... --classfiles <path> ... --json <file>");
    System.out.println();
    System.out.println("<execfiles>         : list of JaCoCo *.exec files to read");
    System.out.println("--classfiles <path> : location of Java class files");
    System.out.println("--json <file>       : output file for the JSON report");
  }

  private static void badArgs(String explanation) {
    printUsage();
    System.out.println(explanation);
    System.exit(2);
  }

  // ==========================================================================
  // === COVERAGE REPORT ======================================================
  // ==========================================================================

  public static File writeReport(Map<String, Map<String, List<Integer>>> report, String outPath)
      throws IOException {
    File outFile = new File(outPath);
    ObjectWriter writer =
        new ObjectMapper()
            .writer()
            .with(
                new DefaultPrettyPrinter()
                    .withoutSpacesInObjectEntries()
                    .withArrayIndenter(DefaultPrettyPrinter.NopIndenter.instance));

    writer.writeValue(outFile, report);

    return outFile;
  }

  /**
   * @param execFiles
   * @param classFiles
   * @return Mapping of covered classes -> methods -> lines
   * @throws IOException
   */
  public static Map<String, Map<String, List<Integer>>> generateReport(
      List<String> execFiles, List<String> classFiles) throws IOException {
    Map<String, Map<String, List<Integer>>> classEntries = new HashMap<>();

    for (IClassCoverage classCoverage : analyzeCoverage(execFiles, classFiles)) {
      // only include classes that have some covered instructions
      if (classCoverage.getInstructionCounter().getCoveredCount() == 0) {
        continue;
      }

      String className = classCoverage.getName().replace('/', '.');
      classEntries.put(className, aggregateClassCoverage(classCoverage));
    }

    return classEntries;
  }

  /**
   * @param execFiles
   * @param classFiles
   * @return
   * @throws IOException
   */
  public static Collection<IClassCoverage> analyzeCoverage(
      List<String> execFiles, List<String> classFiles) throws IOException {
    // load exec files
    ExecFileLoader loader = new ExecFileLoader();

    for (String path : execFiles) {
      File file = new File(path);
      if (!file.exists()) {
        throw new IllegalArgumentException("exec file does not exist: " + path);
      }
      try (FileInputStream fis = new FileInputStream(file)) {
        loader.load(fis);
      }
    }

    // analyze classes
    CoverageBuilder coverageBuilder = new CoverageBuilder();
    Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), coverageBuilder);

    for (String path : classFiles) {
      File file = new File(path);
      if (!file.exists()) {
        throw new IllegalArgumentException("classfiles path does not exist: " + path);
      }
      analyzer.analyzeAll(file);
    }

    return coverageBuilder.getClasses();
  }

  /**
   * @param classCoverage
   * @return
   */
  public static Map<String, List<Integer>> aggregateClassCoverage(IClassCoverage classCoverage) {
    Map<String, List<Integer>> methodEntries = new HashMap<>();

    for (IMethodCoverage methodCoverage : classCoverage.getMethods()) {
      // only include methods with some coverage
      if (methodCoverage.getInstructionCounter().getCoveredCount() == 0) {
        continue;
      }

      // method identifier with name and descriptor without return type
      String descriptor = methodCoverage.getDesc();
      descriptor = descriptor.substring(0, descriptor.indexOf(')') + 1);
      String methodId = methodCoverage.getName() + descriptor;
      methodEntries.put(methodId, aggregateMethodCoverage(methodCoverage));
    }

    return methodEntries;
  }

  /**
   * @param methodCoverage
   * @return
   */
  public static List<Integer> aggregateMethodCoverage(IMethodCoverage methodCoverage) {
    int firstLine = methodCoverage.getFirstLine();
    int lastLine = methodCoverage.getLastLine();

    // collect line numbers if source line info is available
    // ISourceNode.UNKNOWN_LINE if no debug info
    List<Integer> coveredLines = new ArrayList<>();
    if (firstLine == ISourceNode.UNKNOWN_LINE || lastLine == ISourceNode.UNKNOWN_LINE) {
      return coveredLines;
    }

    for (int ln = firstLine; ln <= lastLine; ln++) {
      ILine line = methodCoverage.getLine(ln);
      if (line == null) { // consider the whole method covered if we can't get line details
        coveredLines.clear();
        break;
      }

      int status = line.getStatus();
      if (status == ICounter.FULLY_COVERED || status == ICounter.PARTLY_COVERED) {
        coveredLines.add(ln);
      }
    }

    return coveredLines;
  }
}
