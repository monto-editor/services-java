package monto.service.java8;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.zeromq.ZContext;

import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.resources.ResourceServer;
import monto.service.types.ServiceID;

public class JavaServices {

	public static final ServiceID JAVA_TOKENIZER = new ServiceID("javaTokenizer");
	public static final ServiceID JAVA_PARSER = new ServiceID("javaParser");
    public static final ServiceID JAVA_OUTLINER = new ServiceID("javaOutliner");
	public static final ServiceID JAVA_CODE_COMPLETION = new ServiceID("javaCodeCompletion");
	private static ResourceServer resourceServer;

	public static void main(String[] args) throws Exception {
        ZContext context = new ZContext(1);
        List<MontoService> services = new ArrayList<>();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                System.out.println("terminating...");
                try {
                	for (MontoService service : services)
                		service.stop();
					resourceServer.stop();
				} catch (Exception e) {
					e.printStackTrace();
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
                .addOption("cd", false, "enable java dynamic code completioner")
                .addOption("address", true, "address of services")
                .addOption("registration", true, "address of broker registration")
                .addOption("configuration", true, "address of configuration messages")
                .addOption("resources", true, "port for http resource server")
                .addOption("dyndeps", true, "port for dynamic dependencies registrations");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);
          
        ZMQConfiguration zmqConfig = new ZMQConfiguration(
        		context,
        		cmd.getOptionValue("address"),
        		cmd.getOptionValue("registration"),
        		cmd.getOptionValue("configuration"),
                cmd.getOptionValue("dyndeps"),
        		Integer.parseInt(cmd.getOptionValue("resources")));

        resourceServer = new ResourceServer(JavaServices.class.getResource("/images").getPath(), zmqConfig.getResourcePort());
        resourceServer.start();
        
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
            if (cmd.hasOption("cd")) {
                services.add(new JavaDynamicCodeCompletion(zmqConfig));
            } else {
                services.add(new JavaCodeCompletion(zmqConfig));
            }
        }

        for (MontoService service : services) {
            service.start();
        }
    }
}
