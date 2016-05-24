package monto.connection;

import monto.service.gson.GsonMonto;
import monto.service.product.ProductMessage;

import java.util.Optional;

public class Sink {
    private Subscribe connection;
    private String subscription;

    public Sink(Subscribe connection, String subscription) {
        this.connection = connection;
        this.subscription = subscription;
    }

    public void connect() {
        connection.connect();
        connection.subscribe(subscription);
    }

    public Optional<ProductMessage> receiveMessage() {
        return connection.receiveMessage()
                .flatMap(msg -> {
                    try {
                        return Optional.of(GsonMonto.fromJson(msg, ProductMessage.class));
                    } catch (Exception e) {
                        return Optional.empty();
                    }
                });
    }

    public void close() throws Exception {
        connection.close();
    }
}
