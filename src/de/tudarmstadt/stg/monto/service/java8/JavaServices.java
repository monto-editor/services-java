package de.tudarmstadt.stg.monto.service.java8;

import de.tudarmstadt.stg.monto.service.MontoService;
import de.tudarmstadt.stg.monto.service.message.Language;
import de.tudarmstadt.stg.monto.service.message.Product;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Context;

import java.util.ArrayList;
import java.util.List;

public class JavaServices {

    public static final Language JSON = new Language("json");
    public static final Product TOKENS = new Product("tokens");
    public static final Product AST = new Product("ast");
    public static final Product OUTLINE = new Product("outline");
    public static final Product COMPLETIONS = new Product("completions");

    public static void main(String[] args) {
        String addr = "tcp://localhost:";
        List<MontoService> services = new ArrayList<>();
        Context context = ZMQ.context(1);

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                System.out.println("terminating...");
                context.term();
                for (MontoService service : services) {
                    service.stop();
                }
                System.out.println("terminated");
            }
        });

        services.add(new JavaTokenizer(addr + 5010, context));
        services.add(new JavaParser(addr + 5011, context));
        services.add(new JavaOutliner(addr + 5012, context));
        services.add(new JavaCodeCompletion(addr + 5013, context));

        for (MontoService service : services) {
            service.start();
        }
    }
}
