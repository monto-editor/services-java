package monto.service.java8;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.gson.GsonMonto;
import monto.service.product.Products;
import monto.service.registration.SourceDependency;
import monto.service.request.Request;
import monto.service.source.SourceMessage;
import monto.service.types.Languages;
import monto.service.types.Source;

public class JavaLogicalNameExtractor extends MontoService {
  public JavaLogicalNameExtractor(ZMQConfiguration zmqConfig) {
    super(
        zmqConfig,
        JavaServices.LOGICAL_NAME_EXTRACTOR,
        "JavaLogicalNameExtractor",
        "Extracts the qualified package and class name for every Java Source",
        Languages.JAVA,
        Products.LOGICAL_SOURCE_NAME,
        options(),
        dependencies(new SourceDependency(Languages.JAVA)));
  }

  private static Pattern CLASS_NAME_PATTERN = Pattern.compile("class\\s+(\\w+)[\\s\\w.]*\\{");
  private static Pattern PACKAGE_NAME_PATTERN = Pattern.compile("package\\s+([\\w.]+);");

  @Override
  public void onRequest(Request request) throws Exception {
    Source source = request.getSource();
    Optional<SourceMessage> maybeSourceMessage = request.getSourceMessage(source);
    if (maybeSourceMessage.isPresent()) {
      SourceMessage sourceMessage = maybeSourceMessage.get();
      Matcher classNameMatcher = CLASS_NAME_PATTERN.matcher(sourceMessage.getContents());
      Matcher packageNameMatcher = PACKAGE_NAME_PATTERN.matcher(sourceMessage.getContents());

      if (classNameMatcher.find()) {
        String className = classNameMatcher.group(1);
        String packageName = "";
        if (packageNameMatcher.find()) {
          packageName = packageNameMatcher.group(1);
        }

        String logicalName;
        if (!packageName.isEmpty()) {
          logicalName = packageName + "." + className;
        } else {
          logicalName = className;
        }

        sendProductMessage(
            sourceMessage.getId(),
            source,
            Products.LOGICAL_SOURCE_NAME,
            Languages.JAVA,
            GsonMonto.toJsonTree(new Source(source.getPhysicalName(), logicalName))
        );
      } else {
        System.err.println("JavaLogicalNameExtractor couldn't find class name for " + source);
      }
    }
  }
}
