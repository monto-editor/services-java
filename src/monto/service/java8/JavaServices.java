package monto.service.java8;

import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.types.ServiceID;

import org.apache.commons.cli.*;
import org.zeromq.ZContext;

import java.util.ArrayList;
import java.util.List;

public class JavaServices {

	public static final ServiceID JAVA_TOKENIZER = new ServiceID("javaTokenizer");
	public static final ServiceID JAVA_PARSER = new ServiceID("javaParser");
    public static final ServiceID JAVA_OUTLINER = new ServiceID("javaOutliner");
	public static final ServiceID JAVA_CODE_COMPLETION = new ServiceID("javaCodeCompletion");

	public static void main(String[] args) throws ParseException {
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
                .addOption("registration", true, "address of broker registration")
                .addOption("configuration", true, "address of configuration messages");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);
          
        ZMQConfiguration zmqConfig = new ZMQConfiguration(
        		context,
        		cmd.getOptionValue("address"),
        		cmd.getOptionValue("registration"),
        		cmd.getOptionValue("configuration"));

        if (cmd.hasOption("t")) {
            services.add(new JavaTokenizer(zmqConfig));
        }
        if (cmd.hasOption("p")) {
            services.add(new JavaParser(zmqConfig));
        }
        if (cmd.hasOption("o")) {
            services.add(new JavaOutliner(zmqConfig));
        }
        if (cmd.hasOption("c")) {
            services.add(new JavaCodeCompletion(zmqConfig));
        }

        for (MontoService service : services) {
            service.start();
        }
    }
}
