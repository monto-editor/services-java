package monto.service.java8;

import monto.service.MontoService;
import org.apache.commons.cli.*;
import org.zeromq.ZContext;

import java.util.ArrayList;
import java.util.List;

public class JavaServices {

    public static void main(String[] args) throws ParseException {
        String address = "tcp://*";
        String regAddress = "tcp://*:5004";
        ZContext context = new ZContext(1);
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

        Options options = new Options();
        options.addOption("t", false, "enable java tokenizer")
                .addOption("p", false, "enable java parser")
                .addOption("o", false, "enable java outliner")
                .addOption("c", false, "enable java code completioner")
                .addOption("address", true, "address of services")
                .addOption("registration", true, "address of broker registration");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        if (cmd.hasOption("address")) {
            address = cmd.getOptionValue("address");
        }

        if (cmd.hasOption("registration")) {
            regAddress = cmd.getOptionValue("registration");
        }

        if (cmd.hasOption("t")) {
            services.add(new JavaTokenizer(context, address, regAddress, "javaTokenizer"));
        }
        if (cmd.hasOption("p")) {
            services.add(new JavaParser(context, address, regAddress, "javaParser"));
        }
        if (cmd.hasOption("o")) {
            services.add(new JavaOutliner(context, address, regAddress, "javaOutliner"));
        }
        if (cmd.hasOption("c")) {
            services.add(new JavaCodeCompletion(context, address, regAddress, "javaCodeCompletioner"));
        }

        for (MontoService service : services) {
            service.start();
        }
    }
}
