package monto.service.java8;

import monto.service.MontoService;
import org.zeromq.ZContext;

import java.util.ArrayList;
import java.util.List;

public class JavaServices {

    private static final int regPort = 5009;

    public static void main(String[] args) {
        ZContext context = new ZContext(1);
        String addr = "tcp://localhost:";
        List<MontoService> services = new ArrayList<>();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                System.out.println("terminating...");
                for (MontoService service : services) {
                    service.stop();
                }
                context.destroy();
                System.out.println("everything terminated, good bye");
            }
        });

        services.add(new JavaTokenizer(context, addr, regPort, "javaTokenizer"));
        services.add(new JavaParser(context, addr, regPort, "javaParser"));
        services.add(new JavaOutliner(context, addr, regPort, "javaOutliner"));
        services.add(new JavaCodeCompletion(context, addr, regPort, "javaCodeCompletioner"));

        for (MontoService service : services) {
            service.start();
        }
    }
}
