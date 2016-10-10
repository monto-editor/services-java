package monto.service.java8.launching;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import monto.service.gson.GsonMonto;
import monto.service.launching.ProcessTerminated;
import monto.service.product.ProductMessage;
import monto.service.product.Products;
import monto.service.types.Languages;
import monto.service.types.LongKey;
import monto.service.types.ServiceId;
import monto.service.types.Source;

public class ProcessTerminationThread extends Thread {
  private final Process process;
  private final String launchMode;
  private final int session;
  private final InputStreamProductThread stdoutThread;
  private final InputStreamProductThread stderrThread;
  private final Path workingDirectory;
  private final ServiceId sourceServiceId;
  private final Consumer<ProductMessage> onProductMessage;

  public ProcessTerminationThread(
      Process process,
      String launchMode,
      int session,
      InputStreamProductThread stdoutThread,
      InputStreamProductThread stderrThread,
      Path workingDirectory,
      ServiceId sourceServiceId,
      Consumer<ProductMessage> onProductMessage) {
    this.process = process;
    this.launchMode = launchMode;
    this.session = session;
    this.stdoutThread = stdoutThread;
    this.stderrThread = stderrThread;
    this.workingDirectory = workingDirectory;
    this.sourceServiceId = sourceServiceId;
    this.onProductMessage = onProductMessage;
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

      onProductMessage.accept(
          new ProductMessage(
              new LongKey(-1),
              new Source(String.format("session:%s:%s", launchMode, session)),
              sourceServiceId,
              Products.PROCESS_TERMINATED,
              Languages.JAVA,
              GsonMonto.toJsonTree(new ProcessTerminated(process.exitValue())),
              0));

      System.out.println("Sent PROCESS_TERMINATED product with exit code " + process.exitValue());

      try {
        CompileUtils.removeDirectoryRecursively(workingDirectory);
      } catch (IOException e) {
        e.printStackTrace();
        System.err.println(
            "Couldn't delete working directory: " + workingDirectory.toAbsolutePath().toString());
      }
    }
  }
}
