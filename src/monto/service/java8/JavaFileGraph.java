package monto.service.java8;


import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.dependency.DynamicDependency;
import monto.service.dependency.FileDependency;
import monto.service.dependency.RegisterDynamicDependencies;
import monto.service.gson.GsonMonto;
import monto.service.product.ProductMessage;
import monto.service.product.Products;
import monto.service.registration.ProductDependency;
import monto.service.registration.SourceDependency;
import monto.service.request.Request;
import monto.service.source.SourceMessage;
import monto.service.token.Token;
import monto.service.token.TokenCategory;
import monto.service.types.Languages;
import monto.service.types.ParseException;
import monto.service.types.Source;

import java.util.*;

public class JavaFileGraph extends MontoService {

    private Map<Source, RegisterDynamicDependencies> registerDynamicDependencies = new HashMap<>();

    public JavaFileGraph(ZMQConfiguration zmqConfig) {
        super(zmqConfig,
                JavaServices.JAVA_FILE_GRAPH,
                "Class graph of a file",
                "A class graph service for Java that determines all direct and indirect dependencies",
                Languages.JAVA,
                Products.FILE_GRAPH,
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

        Set<DynamicDependency> dynDeps = findDynamicDependencies(tokens, source);
        sendMissingRequirements(source, dynDeps);
        Set<FileDependency> msgs = getClassDependencyMessage(request, dynDeps);


        return productMessage(
                source.getId(),
                source.getSource(),
                Products.FILE_GRAPH,
                Languages.JAVA,
                GsonMonto.toJson(msgs));
    }

    private Set<FileDependency> getClassDependencyMessage(Request request, Set<DynamicDependency> dependencies) throws ParseException {
        Set<FileDependency> msgs = new HashSet<>();
        Set<Source> srcs = new HashSet<>();
        for (DynamicDependency d : dependencies) {
            ProductMessage fileGraph = request.getProductMessage(d.getSource(), Products.FILE_GRAPH, Languages.JAVA)
                    .orElseThrow(() -> new IllegalArgumentException("No Class Dependencies message in request for " + d.getSource()));
            // TODO: This Gson conversion is not tested, because .orElseThrow() was always executed
            List<FileDependency> fileDependencies = GsonMonto.fromJsonArray((String) fileGraph.getContents(), FileDependency[].class);
            msgs.addAll(fileDependencies);
            srcs.add(d.getSource());
        }
        msgs.add(new FileDependency(request.getSource(), srcs));
        return msgs;
    }

    private Set<DynamicDependency> findDynamicDependencies(ProductMessage tokens, SourceMessage source) throws ParseException {
        Set<DynamicDependency> dynDeps = new HashSet<>();
        int begin = 0;
        boolean foundImport = false;
        for (Token t : GsonMonto.fromJson(tokens, Token[].class)) {
            if (foundImport && t.getCategory() == TokenCategory.DELIMITER && source.getContents().substring(t.getStartOffset(), t.getEndOffset()).equals(";")) {
                String str = source.getContents().substring(begin + 1, t.getStartOffset()).replace(".", "/").trim() + ".java";
                if (str.split("/")[0].equals("java") || !validClassName(str)) {
                    foundImport = false;
                    continue;
                }
                dynDeps.add(new DynamicDependency(new Source(str), JavaServices.JAVA_FILE_GRAPH, Products.FILE_GRAPH, Languages.JAVA));

                foundImport = false;
            } else if (t.getCategory() == TokenCategory.KEYWORD && source.getContents().substring(t.getStartOffset(), t.getEndOffset()).equals("import")) {
                foundImport = true;
                begin = t.getEndOffset();
            }
        }
        return dynDeps;
    }

    public static boolean validClassName(String str) {
        int length = str.length();
        if (length == 0) {
            return false;
        }

        for (int i = 0; i < length; i++) {
            if (Character.isWhitespace(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private void sendMissingRequirements(SourceMessage source, Set<DynamicDependency> dynDeps) {
        RegisterDynamicDependencies regDynDep = new RegisterDynamicDependencies(
                source.getSource(),
                JavaServices.JAVA_FILE_GRAPH,
                dynDeps
        );
        if (!regDynDep.equals(registerDynamicDependencies.get(source.getSource()))) {
            super.registerDynamicDependencies(regDynDep);
            registerDynamicDependencies.put(source.getSource(), regDynDep);
        }
    }
}
