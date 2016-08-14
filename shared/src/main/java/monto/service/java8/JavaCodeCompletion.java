package monto.service.java8;

import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.command.CommandMessage;
import monto.service.completion.Completion;
import monto.service.completion.SourcePositionContent;
import monto.service.dependency.DynamicDependency;
import monto.service.dependency.RegisterCommandMessageDependencies;
import monto.service.gson.GsonMonto;
import monto.service.identifier.Identifier;
import monto.service.product.ProductMessage;
import monto.service.product.Products;
import monto.service.region.Region;
import monto.service.source.SourceMessage;
import monto.service.types.Languages;

public class JavaCodeCompletion extends MontoService {

  public JavaCodeCompletion(ZMQConfiguration zmqConfig) {
    super(
        zmqConfig,
        JavaServices.CODE_COMPLETION,
        "Code Completion",
        "A code completion service for Java",
        Languages.JAVA,
        Products.COMPLETIONS,
        options(),
        dependencies());
  }

  @Override
  public void onCommandMessage(CommandMessage commandMessage) {
    long start = System.nanoTime();

    if (commandMessage.getTag().equals(SourcePositionContent.TAG)) {
      SourcePositionContent sourcePositionContent =
          SourcePositionContent.fromCommandMessage(commandMessage);

      Optional<SourceMessage> maybeSourceMessage =
          commandMessage.getSourceMessage(sourcePositionContent.getSource());
      Optional<ProductMessage> maybeProductMessage =
          commandMessage.getProductMessage(
              sourcePositionContent.getSource(), Products.IDENTIFIER, Languages.JAVA);
      if (maybeSourceMessage.isPresent() && maybeProductMessage.isPresent()) {

        // System.out.println("CommandMessage contains all dependencies.");

        SourceMessage sourceMessage = maybeSourceMessage.get();
        List<Identifier> identifiers =
            GsonMonto.fromJsonArray(maybeProductMessage.get(), Identifier[].class);

        int cursorPosition = sourcePositionContent.getSelection().getStartOffset();

        // Find last non alphanumerical character before cursor position
        String textBeforeCursor =
            new Region(0, cursorPosition).extract(sourceMessage.getContents());
        int startOfCurrentWord = 0;
        Matcher matcher = Pattern.compile("\\W").matcher(textBeforeCursor);
        while (matcher.find()) {
          startOfCurrentWord = matcher.start();
        }

        String toBeCompleted =
            sourceMessage.getContents().substring(startOfCurrentWord, cursorPosition).trim();
        // System.out.println(toBeCompleted);

        int deleteBeginOffset = startOfCurrentWord + 1;
        List<Completion> relevant =
            identifiers
                .stream()
                .filter(identifier -> identifier.getIdentifier().startsWith(toBeCompleted))
                .map(
                    identifier
                        -> new Completion(
                            identifier.getIdentifier(),
                            identifier.getIdentifier(),
                            deleteBeginOffset,
                            cursorPosition - deleteBeginOffset,
                            identifierTypeToIcon(identifier.getType())))
                .collect(Collectors.toList());

        // System.out.printf("Relevant: %s\n", relevant);

        long end = System.nanoTime();
        sendProductMessage(
            sourceMessage.getId(),
            sourceMessage.getSource(),
            Products.COMPLETIONS,
            Languages.JAVA,
            GsonMonto.toJsonTree(relevant),
            end - start);
      } else {
        // Request dependencies
        Set<DynamicDependency> dependencies = new HashSet<>();
        dependencies.add(
            DynamicDependency.sourceDependency(sourcePositionContent.getSource(), Languages.JAVA));
        dependencies.add(
            new DynamicDependency(
                sourcePositionContent.getSource(),
                JavaServices.IDENTIFIER_FINDER,
                Products.IDENTIFIER,
                Languages.JAVA));

        System.out.println(
            "Registering new CommandMessage dependencies: " + dependencies.toString());

        registerCommandMessageDependencies(
            new RegisterCommandMessageDependencies(commandMessage, dependencies));
      }
    }
  }

  private URL identifierTypeToIcon(String identifierType) {
    switch (identifierType) {
      case "import":
        return getResource("package.png");
      case "class":
        return getResource("class-public.png");
      case "interface":
        return getResource("class-package.png");
      case "enum":
        return getResource("package.png");
      case "method":
        return getResource("method-public.png");
      case "field":
        return getResource("field-public.png");
      case "variable":
        return getResource("field-private.png");
      case "generic":
        return getResource("package.png");
      default:
        return null;
    }
  }
}
