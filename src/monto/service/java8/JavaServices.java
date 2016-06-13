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

    public static final ServiceId JAVA_HIGHLIGHTER = new ServiceId("javaHighlighter");
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
        options.addOption("highlighting", false, "enable Java Syntax Highlighting")
                .addOption("antlrparser", false, "enable Java ANTLR parser")
                .addOption("javaccparser", false, "enable JavaCC parser")
                .addOption("outline", false, "enable Java outliner")
                .addOption("codecompletion", false, "enable Java code completioner")
                .addOption("dynamiccodecompletion", false, "enable Java dynamic code completioner")
                .addOption("address", true, "address of services")
                .addOption("registration", true, "address of broker registration")
                .addOption("resources", true, "port for http resource server")
                .addOption("debug", false, "enable debugging output");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        ZMQConfiguration zmqConfig = new ZMQConfiguration(
                context,
                cmd.getOptionValue("address"),
                cmd.getOptionValue("registration"),
                Integer.parseInt(cmd.getOptionValue("resources")));

        resourceServer = new ResourceServer(JavaServices.class.getResource("/icons").toExternalForm(), zmqConfig.getResourcePort());
        resourceServer.start();

        if (cmd.hasOption("highlighting")) {
            services.add(new JavaHighlighter(zmqConfig));
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
        if (cmd.hasOption("debug")) {
            for (MontoService service : services)
                service.enableDebugging();
        }
        for (MontoService service : services) {
            service.start();
        }
    }
}
