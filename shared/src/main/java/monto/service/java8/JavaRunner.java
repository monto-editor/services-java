package monto.service.java8;

import org.apache.commons.lang3.StringEscapeUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.command.CommandMessage;
import monto.service.dependency.DynamicDependency;
import monto.service.dependency.RegisterCommandMessageDependencies;
import monto.service.gson.GsonMonto;
import monto.service.product.Products;
import monto.service.registration.ProductDescription;
import monto.service.launching.LaunchConfiguration;
import monto.service.launching.ProcessTerminated;
import monto.service.launching.StreamOutput;
import monto.service.launching.TerminateProcess;
import monto.service.source.SourceMessage;
import monto.service.types.Languages;
import monto.service.types.LongKey;
import monto.service.types.Source;

public class JavaRunner extends MontoService {
  private JavaCompiler compiler;
  private Map<Integer, ProcessTerminationThread> processThreadMap;

  public JavaRunner(ZMQConfiguration zmqConfig) {
    super(
        zmqConfig,
        JavaServices.RUNNER,
        "Java runtime service",
        "Compiles and runs sources via CommandMessages and reports back stdout and stderr",
        Arrays.asList(
            new ProductDescription(Products.STREAM_OUTPUT, Languages.JAVA),
            new ProductDescription(Products.PROCESS_TERMINATED, Languages.JAVA)
        ),
        options(),
        dependencies());

    compiler = ToolProvider.getSystemJavaCompiler();
    processThreadMap = new HashMap<>();
  }

  @Override
  public void onCommandMessage(CommandMessage commandMessage) {
    if (commandMessage.getTag().equals(LaunchConfiguration.TAG)) {
      LaunchConfiguration launchConfiguration =
          LaunchConfiguration.fromCommandMessage(commandMessage);
      Optional<SourceMessage> maybeMainClassSourceMessage =
          commandMessage.getSourceMessage(launchConfiguration.getMainClassSource());
      // TODO: declare dependencies on imported files
      if (maybeMainClassSourceMessage.isPresent()) {
        try {
          SourceMessage mainClassSourceMessage = maybeMainClassSourceMessage.get();

          Path workingDirectory = Files.createTempDirectory(null);

          compileJavaClass(
              mainClassSourceMessage.getSource().getPhysicalName(),
              mainClassSourceMessage.getContents(),
              workingDirectory.toAbsolutePath().toString());

          if (!mainClassSourceMessage.getSource().getLogicalName().isPresent()) {
            // TODO: send error product instead
            System.err.println(
                mainClassSourceMessage
                    + " doesn't have a logical name.\n"
                    + "JavaRunner needs that to run the class");
          } else {
            Process process =
                Runtime.getRuntime()
                    .exec(
                        "java " + mainClassSourceMessage.getSource().getLogicalName().get(),
                        new String[0],
                        workingDirectory.toFile());

            InputStreamToProductThread stdoutThread =
                new InputStreamToProductThread(
                    StreamOutput.SourceStream.OUT,
                    commandMessage.getSession(),
                    process.getInputStream());
            InputStreamToProductThread stderrThread =
                new InputStreamToProductThread(
                    StreamOutput.SourceStream.ERR,
                    commandMessage.getSession(),
                    process.getErrorStream());

            stdoutThread.start();
            stderrThread.start();

            ProcessTerminationThread processTerminationThread =
                new ProcessTerminationThread(process, commandMessage.getSession(), stdoutThread,
                    stderrThread, workingDirectory);

            processThreadMap.put(commandMessage.getSession(), processTerminationThread);

            processTerminationThread.start();
          }
        } catch (IOException e) {
          e.printStackTrace();
        }

      } else {
        Set<DynamicDependency> dependencies = new HashSet<>();
        dependencies.add(
            DynamicDependency.sourceDependency(
                launchConfiguration.getMainClassSource(), Languages.JAVA));
        registerCommandMessageDependencies(
            new RegisterCommandMessageDependencies(commandMessage, dependencies));
      }
    } else if (commandMessage.getTag().equals(TerminateProcess.TAG)) {
      // CommandMessage doesn't need to be parsed into content, because no additional information is
      // needed for termination
      ProcessTerminationThread processTerminationThread =
          processThreadMap.get(commandMessage.getSession());
      if (processTerminationThread != null) {
        processTerminationThread.interrupt();
      }
    }
  }

  public void removeDirectoryRecursively(Path directory) throws IOException {
    if (Files.exists(directory)) {
      Files.walkFileTree(
          directory,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                throws IOException {
              Files.delete(dir);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.delete(file);
              return FileVisitResult.CONTINUE;
            }
          });
    }
    Files.createDirectory(directory);
  }

  private boolean compileJavaClass(String javaPhysicalFileName, String code, String outputDirectory)
      throws IOException {
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

    JavaFileObject file = new JavaSourceFromString(javaPhysicalFileName, code);

    Iterable<? extends JavaFileObject> compilationUnits = Collections.singletonList(file);
    JavaCompiler.CompilationTask task =
        compiler.getTask(
            null, null, diagnostics, Arrays.asList("-d", outputDirectory), null, compilationUnits);

    boolean success = task.call();
    for (Diagnostic diagnostic : diagnostics.getDiagnostics()) {
      System.out.println(diagnostic.getCode());
      System.out.println(diagnostic.getKind());
      System.out.println(diagnostic.getPosition());
      System.out.println(diagnostic.getStartPosition());
      System.out.println(diagnostic.getEndPosition());
      System.out.println(diagnostic.getSource());
      System.out.println(diagnostic.getMessage(null));
    }
    System.out.println("Success: " + success);
    return success;
  }

  class ProcessTerminationThread extends Thread {
    private final Process process;
    private final int session;
    private final InputStreamToProductThread stdoutThread;
    private final InputStreamToProductThread stderrThread;
    private final Path workingDirectory;

    public ProcessTerminationThread(
        Process process,
        int session,
        InputStreamToProductThread stdoutThread,
        InputStreamToProductThread stderrThread,
        Path workingDirectory) {
      this.process = process;
      this.session = session;
      this.stdoutThread = stdoutThread;
      this.stderrThread = stderrThread;
      this.workingDirectory = workingDirectory;
    }

    @Override
    public void run() {
      try {
        process.waitFor();
      } catch (InterruptedException e) {
        // interrupt on this thread was called
        // This means the IDE user wants to terminate the process
        System.out.printf("Process %s is going to be destroyed\n", process);
        process.destroy();
        try {
          process.waitFor();
          // TODO: this waits forever, if process doesn't respond to termination signal
        } catch (InterruptedException e1) {
          // is thrown, if thread is interrupted again (doesn't happen atm)
          // TODO: force terminate?
          e1.printStackTrace();
        }
      } finally {
        System.out.println("process exited\nwaiting for stream thread to join");
        try {
          stdoutThread.join();
          System.out.println("stdout joined");
          stderrThread.join();
          System.out.println("stderr joined");
        } catch (InterruptedException e) {
          // TODO: provide some feedback, if this happens
          e.printStackTrace();
        }

        sendProductMessage(
            new LongKey(-1),
            new Source("session:run:" + session),
            Products.PROCESS_TERMINATED,
            Languages.JAVA,
            GsonMonto.toJsonTree(new ProcessTerminated(process.exitValue(), session)));

        System.out.println("Sent exitValue product " + process.exitValue());

        try {
          removeDirectoryRecursively(workingDirectory);
        } catch (IOException e) {
          System.err.println("Couldn't delete working directory of JavaRunner");
          e.printStackTrace();
        }
      }
    }
  }

  class InputStreamToProductThread extends Thread {
    private final StreamOutput.SourceStream sourceStream;
    private final int session;
    private final InputStream inputStream;

    public InputStreamToProductThread(
        StreamOutput.SourceStream sourceStream, int session, InputStream inputStream) {
      this.sourceStream = sourceStream;
      this.session = session;
      this.inputStream = inputStream;
    }

    @Override
    public void run() {
      try {
        byte[] bytes = new byte[100];
        int read = 0;

        while (read != -1) {
          read = inputStream.read(bytes);
          if (read > 0) {
            String data = new String(bytes, 0, read, Charset.forName("UTF-8"));
            sendProductMessage(
                new LongKey(-1),
                new Source("session:run:" + session),
                Products.STREAM_OUTPUT,
                Languages.JAVA,
                GsonMonto.toJsonTree(new StreamOutput(sourceStream, data, session)));
            log(System.out, "read some data: " + StringEscapeUtils.escapeJava(data));
          }
        }
        log(System.out, "reached EOF");
      } catch (IOException e) {
        log(System.err, "encountered " + e.getClass().getName() + ": " + e.getMessage());
        e.printStackTrace();
      }
      log(System.out, "is terminating");
    }

    private void log(PrintStream stream, String message) {
      stream.printf("%s (%s, %s) %s\n", getClass().getSimpleName(), sourceStream, session, message);
    }
  }

  class JavaSourceFromString extends SimpleJavaFileObject {
    final String code;

    JavaSourceFromString(String physicalName, String code) {
      super(URI.create("string:///" + physicalName), Kind.SOURCE);
      this.code = code;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return code;
    }
  }
}
