package monto.service.java8;

import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.resources.ResourceServer;
import monto.service.types.ServiceId;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.zeromq.ZContext;

import java.util.ArrayList;
import java.util.List;

public class JavaServices {

    public static final ServiceId JAVA_TOKENIZER = new ServiceId("javaTokenizer");
    public static final ServiceId JAVA_ANTLR_PARSER = new ServiceId("javaAntlrParser");
    public static final ServiceId JAVA_JAVACC_PARSER = new ServiceId("javaJavaCCParser");
    public static final ServiceId JAVA_OUTLINER = new ServiceId("javaOutliner");
    public static final ServiceId JAVA_CODE_COMPLETION = new ServiceId("javaCodeCompletion");
    public static final ServiceId JAVA_FILE_DEPENDENCIES = new ServiceId("javaFileDependencies");
    public static final ServiceId JAVA_FILE_GRAPH = new ServiceId("javaFileGraph");
    public static final ServiceId JAVA_IDENTIFIER_FINDER = new ServiceId("javaIdentifierFinder");

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

        resourceServer = new ResourceServer(JavaServices.class.getResource("/icons").toExternalForm(), zmqConfig.getResourcePort());
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
            services.add(new JavaIdentifierFinder(zmqConfig));
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
            for (MontoService service : services)
                service.enableDebugging();
        }
        for (MontoService service : services) {
            service.start();
        }
    }
}
