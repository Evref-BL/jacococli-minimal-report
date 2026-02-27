[![Build](https://github.com/Evref-BL/jacococli-minimal-report/actions/workflows/build.yml/badge.svg)](https://github.com/Evref-BL/jacococli-minimal-report/actions/workflows/build.yml)
[![Javadoc](https://img.shields.io/badge/JavaDoc-Online-green)](https://Evref-BL.github.io/jacococli-minimal-report/)

# jacococli-minimal-report

Generate minimal JSON coverage reports from JaCoCo execution data.


## Motivation

The standard jacococli report command produces reports whose size depends on the total number of analyzed classes.
Even if only a small portion of the codebase is exercised, all classes are included in the output.

`jacococli-minimal-report` emits a report that contains only the methods with at least one covered instruction.
Uncovered classes and methods are omitted entirely.
As a result, the output size depends on the amount of actual coverage data rather than on the size of the analyzed codebase.


## Usage

```sh
java -jar jacococli-report.jar <execfiles...> --classfiles <classpaths...> --json <output-file>
```

`<execfiles...>`: One or more JaCoCo .exec files.
`--classfiles <classpaths...>`: One or more directories or JAR files containing compiled .class files.
`--json <output-file>`: Path to the generated JSON report.


## Output format

The generated JSON document is a nested mapping structured as:
```json
{
  "<fully-qualified-class-name>": {
    "<method-name-and-descriptor>": [<line>, <line>, ...]
  }
}
```
Only lines reported as `FULLY_COVERED` or `PARTLY_COVERED` by JaCoCo are included.
