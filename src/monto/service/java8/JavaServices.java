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
	public static final ServiceID JAVA_ANTLR_PARSER = new ServiceID("javaAntlrParser");
	public static final ServiceID JAVA_JAVACC_PARSER = new ServiceID("javaJavaCCParser");
    public static final ServiceID JAVA_OUTLINER = new ServiceID("javaOutliner");
	public static final ServiceID JAVA_CODE_COMPLETION = new ServiceID("javaCodeCompletion");
    public static final ServiceID JAVA_FILE_DEPENDENCIES = new ServiceID("javaFileDependencies");
    public static final ServiceID JAVA_FILE_GRAPH = new ServiceID("javaFileGraph");

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
        options.addOption("tokenizer", false, "enable Java tokenizer")
               .addOption("antlrparser", false, "enable Java ANTLR parser")
               .addOption("javaccparser", false, "enable JavaCC parser")
               .addOption("outline", false, "enable Java outliner")
               .addOption("codecompletion", false, "enable Java code completioner")
               .addOption("dynamiccodecompletion", false, "enable Java dynamic code completioner")
               .addOption("filedependencies", false, "enable Java file dependencies")
               .addOption("address", true, "address of services")
               .addOption("registration", true, "address of broker registration")
               .addOption("configuration", true, "address of configuration messages")
               .addOption("resources", true, "port for http resource server")
               .addOption("dyndeps", true, "port for dynamic dependencies registrations")
               .addOption("debug", false, "enable debugging output");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);
          
        ZMQConfiguration zmqConfig = new ZMQConfiguration(
        		context,
        		cmd.getOptionValue("address"),
        		cmd.getOptionValue("registration"),
        		cmd.getOptionValue("configuration"),
                cmd.getOptionValue("dyndeps"),
        		Integer.parseInt(cmd.getOptionValue("resources")));

        resourceServer = new ResourceServer(JavaServices.class.getResource("/images").toExternalForm(), zmqConfig.getResourcePort());
        resourceServer.start();
        
        if (cmd.hasOption("tokenizer")) {
            services.add(new JavaTokenizer(zmqConfig));
        }
        if (cmd.hasOption("javaccparser")) {
            services.add(new JavaJavaCCParser(zmqConfig));
        }
        if (cmd.hasOption("antlrparser")) {
            services.add(new ANTLRJavaParser(zmqConfig));
        }
        if (cmd.hasOption("outline")) {
            services.add(new JavaOutliner(zmqConfig));
        }
        if (cmd.hasOption("codecompletion")) {
        	services.add(new JavaCodeCompletion(zmqConfig));
        }
        if (cmd.hasOption("dynamiccodecompletion")) {
                services.add(new JavaDynamicCodeCompletion(zmqConfig));
        }
        if (cmd.hasOption("filedependencies")) {
            services.add(new JavaFileDependencies(zmqConfig));
            services.add(new JavaFileGraph(zmqConfig));
        }
        if (cmd.hasOption("debug")) {
        	for(MontoService service : services)
        		service.enableDebugging();
        }
        for (MontoService service : services) {
            service.start();
        }
    }
}
