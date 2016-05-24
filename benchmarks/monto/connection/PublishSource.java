package monto.connection;

import monto.service.gson.GsonMonto;
import monto.service.source.SourceMessage;

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
            connection.sendMessage(GsonMonto.toJson(message));
        } catch (Exception e) {
            System.err.print(e);
        }
    }


    public void close() {
        connection.close();
    }
}
