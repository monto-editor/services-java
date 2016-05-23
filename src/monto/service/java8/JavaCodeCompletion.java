package monto.service.java8;

import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.completion.Completion;
import monto.service.gson.GsonMonto;
import monto.service.identifier.Identifier;
import monto.service.product.ProductMessage;
import monto.service.product.Products;
import monto.service.registration.ProductDependency;
import monto.service.registration.SourceDependency;
import monto.service.request.Request;
import monto.service.source.SourceMessage;
import monto.service.types.Languages;

import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;

public class JavaCodeCompletion extends MontoService {

    public JavaCodeCompletion(ZMQConfiguration zmqConfig) {
        super(zmqConfig,
                JavaServices.JAVA_CODE_COMPLETION,
                "Code Completion",
                "A code completion service for Java",
                Languages.JAVA,
                Products.COMPLETIONS,
                options(),
                dependencies(
                        new SourceDependency(Languages.JAVA),
                        new ProductDependency(JavaServices.JAVA_IDENTIFIER_FINDER, Products.IDENTIFIER, Languages.JAVA)
                ));
    }

    @Override
    public void onRequest(Request request) {
        long start = System.nanoTime();

        SourceMessage sourceMessage = request.getSourceMessage()
                .orElseThrow(() -> new IllegalArgumentException("No source message in request"));
        ProductMessage identifierMessage = request.getProductMessage(Products.IDENTIFIER, Languages.JAVA)
                .orElseThrow(() -> new IllegalArgumentException("No identifier message in request"));

        List<Identifier> identifiers = GsonMonto.fromJsonArray(identifierMessage, Identifier[].class);

        List<Completion> relevant =
                identifiers
                        .stream()
                        //.filter(comp -> comp.getReplacement().startsWith(toBeCompleted))
                        .map(identifier -> new Completion(
                                identifier.getType(),
                                identifier.getIdentifier(),
                                //sourceMessage.getSelection().get().getStartOffset(),
                                identifierTypeToIcon(identifier.getType()))
                        )
                        .collect(Collectors.toList());

//        System.out.printf("Relevant: %s\n", relevant);

        long end = System.nanoTime();

        sendProductMessage(
                sourceMessage.getId(),
                sourceMessage.getSource(),
                Products.COMPLETIONS,
                Languages.JAVA,
                GsonMonto.toJsonTree(relevant),
                identifierMessage.getTime() + end - start);
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
