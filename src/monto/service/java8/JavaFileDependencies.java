package monto.service.java8;

import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.dependency.FileDependencies;
import monto.service.dependency.FileDependency;
import monto.service.dependency.RegisterDynamicDependencies;
import monto.service.product.ProductMessage;
import monto.service.product.Products;
import monto.service.registration.ProductDependency;
import monto.service.registration.SourceDependency;
import monto.service.request.Request;
import monto.service.source.SourceMessage;
import monto.service.token.Token;
import monto.service.token.TokenCategory;
import monto.service.token.Tokens;
import monto.service.types.Languages;
import monto.service.types.ParseException;
import monto.service.types.Source;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class JavaFileDependencies extends MontoService {

    private Map<Source, RegisterDynamicDependencies> registerDynamicDependencies = new HashMap<>();

    public JavaFileDependencies(ZMQConfiguration zmqConfig) {
        super(zmqConfig,
                JavaServices.JAVA_FILE_DEPENDENCIES,
                "File dependencies of a class",
                "A file dependency service for Java that determines all direct depenencies",
                Languages.JAVA,
                Products.FILE_DEPENDENCIES,
                options(),
                dependencies(
                        new SourceDependency(Languages.JAVA),
                        new ProductDependency(JavaServices.JAVA_TOKENIZER, Products.TOKENS, Languages.JAVA)
                ));
    }

    @Override
    public ProductMessage onRequest(Request request) throws Exception {
        SourceMessage source = request.getSourceMessage(request.getSource())
                .orElseThrow(() -> new IllegalArgumentException("No Source message in request"));

        ProductMessage tokens = request.getProductMessage(request.getSource(), Products.TOKENS, Languages.JAVA)
                .orElseThrow(() -> new IllegalArgumentException("No Tokens message in request"));

        FileDependency deps = findFileDepenencies(tokens, source);

        return productMessage(
                source.getId(),
                source.getSource(),
                Products.FILE_DEPENDENCIES,
                Languages.JAVA,
                FileDependencies.encode(deps));
    }

    private FileDependency findFileDepenencies(ProductMessage tokens, SourceMessage source) throws ParseException {
        Set<Source> deps = new HashSet<>();
        int begin = 0;
        boolean foundImport = false;
        for (Token t : Tokens.decodeTokenMessage(tokens)) {
            if (foundImport && t.getCategory() == TokenCategory.DELIMITER && source.getContents().substring(t.getStartOffset(), t.getEndOffset()).equals(";")) {
                String str = source.getContents().substring(begin + 1, t.getStartOffset()).replace(".", "/").toLowerCase().trim() + ".java";
                if (str.split("/")[0].equals("java")) {
                    continue;
                }
                deps.add(new Source(str));

                foundImport = false;
            } else if (t.getCategory() == TokenCategory.KEYWORD && source.getContents().substring(t.getStartOffset(), t.getEndOffset()).equals("import")) {
                foundImport = true;
                begin = t.getEndOffset();
            }
        }
        return new FileDependency(source.getSource(), deps);
    }
}

