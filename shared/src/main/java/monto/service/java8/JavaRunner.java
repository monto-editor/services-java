package monto.service.java8;

import java.io.IOException;
import java.io.InputStream;
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
import monto.service.run.ProcessRunContent;
import monto.service.run.ProcessTerminateContent;
import monto.service.run.StreamOutput;
import monto.service.source.SourceMessage;
import monto.service.types.Languages;

public class JavaRunner extends MontoService {
  private JavaCompiler compiler;
  private Map<Integer, ProcessTerminationThread> processThreadMap;

  public JavaRunner(ZMQConfiguration zmqConfig) {
    super(
        zmqConfig,
        JavaServices.RUNNER,
        "Java runtime service",
        "Compiles and runs sources via CommandMessages and reports back stdout and stderr",
        Collections.EMPTY_LIST,
        options(),
        dependencies());

    compiler = ToolProvider.getSystemJavaCompiler();
    processThreadMap = new HashMap<>();
  }

  @Override
  public void onCommandMessage(CommandMessage commandMessage) {
    if (commandMessage.getTag().equals(ProcessRunContent.TAG)) {
      ProcessRunContent processRunContent = ProcessRunContent.fromCommandMessage(commandMessage);
      Optional<SourceMessage> maybeMainClassSourceMessage =
          commandMessage.getSourceMessage(processRunContent.getMainClassSource());
      // TODO: declare dependencies on imported files
      if (maybeMainClassSourceMessage.isPresent()) {
        try {
          SourceMessage mainClassSourceMessage = maybeMainClassSourceMessage.get();

          Path workingDirectory = Files.createTempDirectory(null);

          compileJavaClass(
              mainClassSourceMessage.getSource().getSource(),
              mainClassSourceMessage.getContents(),
              workingDirectory.toAbsolutePath().toString());

          Process process =
              Runtime.getRuntime()
                  .exec(
                      "java " + mainClassSourceMessage.getSource().getSource(),
                      new String[0],
                      workingDirectory.toFile());

          InputStreamToCommandUpdateThread stdoutThread =
              new InputStreamToCommandUpdateThread(
                  StreamOutput.SourceStream.OUT, commandMessage, process.getInputStream());
          InputStreamToCommandUpdateThread stderrThread =
              new InputStreamToCommandUpdateThread(
                  StreamOutput.SourceStream.ERR, commandMessage, process.getErrorStream());

          stdoutThread.start();
          stderrThread.start();

          ProcessTerminationThread processTerminationThread =
              new ProcessTerminationThread(process, stdoutThread, stderrThread, workingDirectory);

          processThreadMap.put(commandMessage.getSession(), processTerminationThread);

          processTerminationThread.start();

        } catch (IOException e) {
          e.printStackTrace();
        }

      } else {
        Set<DynamicDependency> dependencies = new HashSet<>();
        dependencies.add(
            DynamicDependency.sourceDependency(
                processRunContent.getMainClassSource(), Languages.JAVA));
        registerCommandMessageDependencies(
            new RegisterCommandMessageDependencies(commandMessage, dependencies));
      }
    } else if (commandMessage.getTag().equals(ProcessTerminateContent.TAG)) {
      // CommandMessage doesn't need to be parsed into content, because no additional information is needed for termination
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

  private boolean compileJavaClass(String javaClassName, String code, String outputDirectory)
      throws IOException {
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

    JavaFileObject file = new JavaSourceFromString(javaClassName, code);

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
    private final InputStreamToCommandUpdateThread stdoutThread;
    private final InputStreamToCommandUpdateThread stderrThread;
    private final Path workingDirectory;

    public ProcessTerminationThread(
        Process process,
        InputStreamToCommandUpdateThread stdoutThread,
        InputStreamToCommandUpdateThread stderrThread,
        Path workingDirectory) {
      this.process = process;
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
        System.out.println("process exited");
        stdoutThread.interrupt();
        stderrThread.interrupt();
        System.out.println("interrupted threads");
        try {
          stdoutThread.join();
          System.out.println("stdout joined");
          stderrThread.join();
          System.out.println("stderr joined");
        } catch (InterruptedException e) {
          // TODO: provide some feedback, if this happens
          e.printStackTrace();
        }

        System.out.println(process.exitValue());

        try {
          removeDirectoryRecursively(workingDirectory);
        } catch (IOException e) {
          System.err.println("Couldn't delete working directory of JavaRunner");
          e.printStackTrace();
        }
      }
    }
  }

  class InputStreamToCommandUpdateThread extends Thread {

    private final StreamOutput.SourceStream sourceStream;
    private final CommandMessage commandMessage;
    private final InputStream inputStream;

    public InputStreamToCommandUpdateThread(
        StreamOutput.SourceStream sourceStream,
        CommandMessage commandMessage,
        InputStream inputStream) {
      this.sourceStream = sourceStream;
      this.commandMessage = commandMessage;
      this.inputStream = inputStream;
    }

    @Override
    public void run() {
      while (true) {
        int availableBytes;
        if (!isInterrupted()) {
          try {
            availableBytes = inputStream.available();
            if (availableBytes != 0) {
              byte[] bytes = new byte[availableBytes];
              int reed = inputStream.read(bytes);
              if (availableBytes != reed) {
                System.err.println("weird");
              }
              sendCommandMessageUpdate(
                  commandMessage,
                  "streamOutput",
                  GsonMonto.toJsonTree(new StreamOutput(sourceStream, bytes)));
              System.out.print(new String(bytes, 0, reed, Charset.forName("UTF-8")));
            } else {
              try {
                sleep(10);
              } catch (InterruptedException e) {
                interrupt();
              }
            }
          } catch (IOException e) {
            e.printStackTrace();
          }
        } else {
          System.out.println("was interrupted");
          return;
        }
      }
    }
  }

  class JavaSourceFromString extends SimpleJavaFileObject {
    final String code;

    JavaSourceFromString(String name, String code) {
      super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
      this.code = code;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return code;
    }
  }
}
