package monto.service.java8;

import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.command.CommandMessage;
import monto.service.command.SourcePositionContent;
import monto.service.completion.Completion;
import monto.service.gson.GsonMonto;
import monto.service.identifier.Identifier;
import monto.service.product.ProductMessage;
import monto.service.product.Products;
import monto.service.region.Region;
import monto.service.registration.ProductDependency;
import monto.service.registration.SourceDependency;
import monto.service.request.Request;
import monto.service.source.SourceMessage;
import monto.service.types.Languages;
import monto.service.types.Source;

public class JavaCodeCompletion extends MontoService {

  private Map<Source, Pair<SourceMessage, List<Identifier>>> identifierCache;

  public JavaCodeCompletion(ZMQConfiguration zmqConfig) {
    super(
        zmqConfig,
        JavaServices.CODE_COMPLETION,
        "Code Completion",
        "A code completion service for Java",
        Languages.JAVA,
        Products.COMPLETIONS,
        options(),
        dependencies(
            new SourceDependency(Languages.JAVA),
            new ProductDependency(
                JavaServices.IDENTIFIER_FINDER, Products.IDENTIFIER, Languages.JAVA)));

    identifierCache = new HashMap<>();
  }

  @Override
  public void onRequest(Request request) {

    Source mainSource = request.getSource();
    SourceMessage sourceMessage =
        request
            .getSourceMessage(mainSource)
            .orElseThrow(() -> new IllegalArgumentException("No source message in request"));
    ProductMessage identifierMessage =
        request
            .getProductMessage(mainSource, Products.IDENTIFIER, Languages.JAVA)
            .orElseThrow(() -> new IllegalArgumentException("No identifier message in request"));

    List<Identifier> identifiers = GsonMonto.fromJsonArray(identifierMessage, Identifier[].class);

    identifierCache.put(mainSource, new ImmutablePair<>(sourceMessage, identifiers));
  }

  @Override
  public void onCommandMessage(CommandMessage commandMessage) {
    long start = System.nanoTime();

    System.out.println(commandMessage);
    if (commandMessage.getTag().equals(CommandMessage.TAG_SOURCE_POSITION)) {
      SourcePositionContent sourcePositionContent = commandMessage.asSourcePosition();

      if (identifierCache.containsKey(sourcePositionContent.getSource())) {
        Pair<SourceMessage, List<Identifier>> sourceMessageListPair =
            identifierCache.get(sourcePositionContent.getSource());

        SourceMessage sourceMessage = sourceMessageListPair.getLeft();
        List<Identifier> identifiers = sourceMessageListPair.getRight();

        int cursorPosition = sourcePositionContent.getSelection().getStartOffset();
        String textBeforeCursor =
            new Region(0, cursorPosition).extract(sourceMessage.getContents());

        int startOfCurrentWord =
            NumberUtils.max(
                0,
                textBeforeCursor.lastIndexOf(' '),
                textBeforeCursor.lastIndexOf('\t'),
                textBeforeCursor.lastIndexOf('.'),
                textBeforeCursor.lastIndexOf(','),
                textBeforeCursor.lastIndexOf(':'),
                textBeforeCursor.lastIndexOf(';'),
                textBeforeCursor.lastIndexOf('"'),
                textBeforeCursor.lastIndexOf('='),
                textBeforeCursor.lastIndexOf('+'),
                textBeforeCursor.lastIndexOf('-'),
                textBeforeCursor.lastIndexOf('*'),
                textBeforeCursor.lastIndexOf('/'),
                textBeforeCursor.lastIndexOf('%'),
                textBeforeCursor.lastIndexOf('('),
                textBeforeCursor.lastIndexOf(')'),
                textBeforeCursor.lastIndexOf('<'),
                textBeforeCursor.lastIndexOf('>'),
                textBeforeCursor.lastIndexOf('{'),
                textBeforeCursor.lastIndexOf('}'),
                textBeforeCursor.lastIndexOf('['),
                textBeforeCursor.lastIndexOf(']'));
        if (startOfCurrentWord != 0) {
          // exclude limit characters, except on document start
          startOfCurrentWord += 1;
        }

        String toBeCompleted =
            sourceMessage.getContents().substring(startOfCurrentWord, cursorPosition).trim();
        System.out.println(toBeCompleted);

        List<Completion> relevant =
            identifiers
                .stream()
                .filter(identifier -> identifier.getIdentifier().startsWith(toBeCompleted))
                .map(
                    identifier
                        -> new Completion(
                            identifier.getIdentifier(),
                            identifier.getIdentifier(),
                            //sourceMessage.getSelection().get().getStartOffset(),
                            identifierTypeToIcon(identifier.getType())))
                .collect(Collectors.toList());

        System.out.printf("Relevant: %s\n", relevant);

        long end = System.nanoTime();
        sendProductMessage(
            sourceMessage.getId(),
            sourceMessage.getSource(),
            Products.COMPLETIONS,
            Languages.JAVA,
            GsonMonto.toJsonTree(relevant),
            end - start);
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
