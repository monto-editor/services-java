package monto.service.java8;

import monto.service.types.ServiceId;

public final class JavaServices {
  public static final ServiceId HIGHLIGHTER = new ServiceId("javaHighlighter");
  public static final ServiceId ANTLR_PARSER = new ServiceId("javaAntlrParser");
  public static final ServiceId JAVACC_PARSER = new ServiceId("javaJavaCCParser");
  public static final ServiceId OUTLINER = new ServiceId("javaOutliner");
  public static final ServiceId CODE_COMPLETIONER = new ServiceId("javaCodeCompletioner");
  public static final ServiceId IDENTIFIER_FINDER = new ServiceId("javaIdentifierFinder");
  public static final ServiceId LOGICAL_NAME_EXTRACTOR = new ServiceId("javaLogicalNameExtractor");
  public static final ServiceId RUNNER = new ServiceId("javaRunner");
  public static final ServiceId DEBUGGER = new ServiceId("javaDebugger");

  private JavaServices() {}
}
