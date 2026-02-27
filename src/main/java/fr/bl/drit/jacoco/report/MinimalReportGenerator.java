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

/**
 * Minimal utility to generate a compact JSON coverage report from JaCoCo execution (".exec") files
 * and Java class files.
 *
 * <p>The produced JSON maps fully-qualified class names to a mapping of method identifiers to a
 * list of covered source line numbers:
 *
 * <pre>{
 *   "com.example.MyClass": {
 *     "myMethod(Ljava/lang/String;)V": [12, 13, 20],
 *     "otherMethod()I": [45]
 *   },
 *   ...
 * }</pre>
 *
 * <p>Only classes and methods that have at least one covered instruction are included. Method
 * identifiers are composed of the method name and <a
 * href=https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-4.html#jvms-4.3.3>descriptor</a>.
 */
public class MinimalReportGenerator {

  private MinimalReportGenerator() {}

  // ==========================================================================
  // === CLI ==================================================================
  // ==========================================================================

  /**
   * Entry point for the minimal report generator CLI.
   *
   * <p>Accepted command-line form:
   *
   * <pre>
   * java -jar jacococli-minimal-report.jar {@literal <execfiles>} ... --classfiles {@literal <path>} ... --json {@literal <file>}
   * </pre>
   *
   * <ul>
   *   <li>All arguments before {@code --classfiles} are treated as paths to JaCoCo {@code *.exec}
   *       files.
   *   <li>All arguments after {@code --classfiles} are treated as paths to Java class files or
   *       directories containing class files.
   *   <li>The <code>--json {@literal <file>}</code> option is required and specifies the output
   *       JSON file to write.
   *   <li>If required arguments are missing, the process prints usage and exits with status {@code
   *       2}.
   * </ul>
   *
   * @param args command-line arguments as described above
   * @throws IOException if an unexpected error occurs during analysis or writing the output file
   *     (propagates underlying I/O or analysis exceptions)
   */
  public static void main(String[] args) throws IOException {
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
            badArgs("Option \"--json\" takes an operand");
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
      badArgs("At least one *.exec file is required");
    }

    if (classFiles.isEmpty()) {
      badArgs("Option \"--classfiles\" is required and at least one path must be given");
    }

    if (outPath == null) {
      badArgs("Option \"--json\" is required");
    }

    // generate the report
    File outFile = writeReport(generateReport(execFiles, classFiles), outPath);

    System.out.println("Report generated: " + outFile.getAbsolutePath());
  }

  /**
   * Prints usage information for the minimal report generator to standard output.
   *
   * <p>The message includes a short description and the accepted command-line syntax. This method
   * is used by {@link #main(String[])} and by argument validation helpers to explain correct
   * invocation.
   */
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

  /**
   * Prints usage information and the provided explanation, then terminates the JVM with exit code
   * {@code 2}.
   *
   * <p>This helper is intended for reporting malformed or missing command-line arguments.
   *
   * @param explanation short explanation of what was wrong with the arguments
   */
  private static void badArgs(String explanation) {
    printUsage();
    System.out.println();
    System.out.println(explanation);
    System.exit(2);
  }

  // ==========================================================================
  // === COVERAGE REPORT ======================================================
  // ==========================================================================

  /**
   * Represents the coverage of a single method as a list of covered source line numbers.
   *
   * <p>If there is no debug information, the list is empty and the whole method should be
   * considered covered.
   */
  public interface LinesCoverage extends List<Integer> {}

  /**
   * Represents the coverage of all covered methods within a single class.
   *
   * <p>The key is a method identifier composed of the method name and its <a
   * href="https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-4.html#jvms-4.3.3">descriptor</a>,
   * and the value is the corresponding {@link LinesCoverage} describing the covered source lines
   * for that method.
   */
  public interface MethodsCoverage extends Map<String, LinesCoverage> {}

  /**
   * Represents the coverage of all covered classes in a report.
   *
   * <p>The key is the fully-qualified class name (using dot notation), and the value is the
   * corresponding {@link MethodsCoverage} describing covered methods and their covered lines.
   */
  public interface ClassesCoverage extends Map<String, MethodsCoverage> {}

  /**
   * Serializes the generated report structure to the given filesystem path as a pretty (but compact
   * object entries) JSON file.
   *
   * <p>The writer configuration attempts to minimize spaces inside object entries while preserving
   * readable array indentation.
   *
   * @param report the coverage report mapping class -> method -> lines
   * @param outPath path to the output JSON file to write
   * @return the {@link File} instance pointing to the written file
   * @throws IOException if writing the file fails
   * @see ObjectMapper
   * @see ObjectWriter
   */
  public static File writeReport(ClassesCoverage report, String outPath) throws IOException {
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
   * Generates the in-memory coverage report structure for the given JaCoCo execution files and
   * class files.
   *
   * <p>The returned map has the shape {@link ClassesCoverage} where the top-level key is the
   * fully-qualified class name (dot-separated), the second-level key is the method identifier
   * (method name + <a
   * href="https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-4.html#jvms-4.3.3">descriptor</a>),
   * and the list contains covered source line numbers for that method (empty list if there is no
   * debug information).
   *
   * <p>Only classes with at least one covered instruction are included. The implementation
   * delegates to {@link #analyzeCoverage(List, List)} to obtain JaCoCo {@link IClassCoverage}
   * objects and to {@link #aggregateClassCoverage(IClassCoverage)} to compute per-method coverage.
   *
   * @param execFiles list of paths to JaCoCo {@code *.exec} files; must contain at least one entry
   * @param classFiles list of paths to class files or directories containing class files; must
   *     contain at least one entry
   * @return the aggregated coverage report mapping classes -> methods -> lines
   * @throws IOException if reading execution or class files fails
   */
  public static ClassesCoverage generateReport(List<String> execFiles, List<String> classFiles)
      throws IOException {
    ClassesCoverage classEntries = (ClassesCoverage) new HashMap<String, MethodsCoverage>();

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
   * Loads execution data from the provided JaCoCo {@code *.exec} files and analyzes the supplied
   * class files to produce a collection of {@link IClassCoverage} objects.
   *
   * <p>Validation and behavior:
   *
   * <ul>
   *   <li>Each path in {@code execFiles} and {@code classFiles} must refer to an existing file; if
   *       a file does not exist an {@link IllegalArgumentException} is thrown.
   *   <li>Exec files are loaded into an {@link ExecFileLoader} and then analyzed together using an
   *       {@link Analyzer} backed by a {@link CoverageBuilder}.
   * </ul>
   *
   * @param execFiles list of JaCoCo execution file paths to load
   * @param classFiles list of class file paths or directories to analyze
   * @return collection of {@link IClassCoverage} instances representing the analyzed classes (may
   *     be empty if no classes matched)
   * @throws IOException if an I/O error occurs while reading files
   * @throws IllegalArgumentException if any provided path does not exist
   * @see ExecFileLoader
   * @see Analyzer
   * @see CoverageBuilder
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
   * Aggregates coverage information for a single class into a map from method identifier to a list
   * of covered source line numbers for that method.
   *
   * <p>Method selection and identifier format:
   *
   * <ul>
   *   <li>Only methods that have at least one covered instruction are included.
   *   <li>The method identifier is composed of the method name and <a
   *       href="https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-4.html#jvms-4.3.3">descriptor</a>.
   * </ul>
   *
   * <p>This method delegates to {@link #aggregateMethodCoverage(IMethodCoverage)} to compute
   * per-method covered line lists.
   *
   * @param classCoverage JaCoCo {@link IClassCoverage} describing the class
   * @return a map whose keys are method identifiers and whose values are lists of covered source
   *     line numbers; may be empty if no methods qualify
   */
  public static MethodsCoverage aggregateClassCoverage(IClassCoverage classCoverage) {
    Map<String, LinesCoverage> methodEntries = new HashMap<>();

    for (IMethodCoverage methodCoverage : classCoverage.getMethods()) {
      // only include methods with some coverage
      if (methodCoverage.getInstructionCounter().getCoveredCount() == 0) {
        continue;
      }

      // method identifier with name and descriptor
      String methodId = methodCoverage.getName() + methodCoverage.getDesc();
      methodEntries.put(methodId, aggregateMethodCoverage(methodCoverage));
    }

    return (MethodsCoverage) methodEntries; //
  }

  /**
   * Aggregates the covered source line numbers for a single method. Lines whose status is {@link
   * ICounter#FULLY_COVERED ICounter.FULLY_COVERED} or {@link ICounter#PARTLY_COVERED} are added to
   * the returned list.
   *
   * <p>If the method has no source line debug information, an empty list is returned, effectively
   * considering the whole method as covered but without line granularity.
   *
   * @param methodCoverage JaCoCo {@link IMethodCoverage} describing the method
   * @return ordered list of covered source line numbers within the method's {@code [firstLine,
   *     lastLine]} interval; may be empty if no line information exists
   * @see ILine
   * @see ICounter
   */
  public static LinesCoverage aggregateMethodCoverage(IMethodCoverage methodCoverage) {
    int firstLine = methodCoverage.getFirstLine();
    int lastLine = methodCoverage.getLastLine();

    // collect line numbers if source line info is available
    // ISourceNode.UNKNOWN_LINE if no debug info
    List<Integer> coveredLines = new ArrayList<>();
    if (firstLine == ISourceNode.UNKNOWN_LINE || lastLine == ISourceNode.UNKNOWN_LINE) {
      return (LinesCoverage) coveredLines;
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

    return (LinesCoverage) coveredLines;
  }
}
