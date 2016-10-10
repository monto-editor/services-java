package monto.service.java8.launching;

import org.apache.commons.lang3.StringEscapeUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.function.Consumer;
import monto.service.gson.GsonMonto;
import monto.service.launching.StreamOutput;
import monto.service.product.ProductMessage;
import monto.service.product.Products;
import monto.service.types.Languages;
import monto.service.types.LongKey;
import monto.service.types.ServiceId;
import monto.service.types.Source;

public class InputStreamProductThread extends Thread {
  private final StreamOutput.SourceStream sourceStream;
  private final String launchMode;
  private final int session;
  private final InputStream inputStream;
  private final ServiceId sourceServiceId;
  private final Consumer<ProductMessage> onProductMessage;

  public InputStreamProductThread(
      StreamOutput.SourceStream sourceStream,
      String launchMode,
      int session,
      InputStream inputStream,
      ServiceId sourceServiceId,
      Consumer<ProductMessage> onProductMessage) {
    this.sourceStream = sourceStream;
    this.session = session;
    this.launchMode = launchMode;
    this.inputStream = inputStream;
    this.sourceServiceId = sourceServiceId;
    this.onProductMessage = onProductMessage;
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
          // TODO test with sync block on service object, sothat sendProduct() must not be synced, because performance
          onProductMessage.accept(
              new ProductMessage(
                  new LongKey(-1),
                  new Source(String.format("session:%s:%s", launchMode, session)),
                  sourceServiceId,
                  Products.STREAM_OUTPUT,
                  Languages.JAVA,
                  GsonMonto.toJsonTree(new StreamOutput(sourceStream, data)),
                  0));
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
