package monto.connection;

import monto.service.source.SourceMessage;
import monto.service.source.SourceMessages;
import org.json.simple.JSONObject;

public class PublishSource {
    private Publish connection;

    public PublishSource(Publish connection) {
        this.connection = connection;
    }

    public void connect() {
        connection.connect();
    }

    public void sendMessage(SourceMessage message) {
        try {
            JSONObject encoding = SourceMessages.encode(message);
            connection.sendMessage(encoding.toJSONString());
        } catch (Exception e) {
            System.err.print(e);
        }
    }


    public void close() {
        connection.close();
    }
}
