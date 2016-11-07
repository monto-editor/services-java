package monto.service.java8;

import monto.service.ZMQConfiguration;
import monto.service.completion.CodeCompletioner;
import monto.service.types.Languages;

public class JavaCodeCompletioner extends CodeCompletioner {

  public JavaCodeCompletioner(ZMQConfiguration zmqConfig) {
    super(
        zmqConfig,
        JavaServices.CODE_COMPLETIONER,
        "Code Completion",
        "A code completion service for Java",
        Languages.JAVA,
        JavaServices.IDENTIFIER_FINDER);
  }
}
