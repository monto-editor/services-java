package monto.connection;

import monto.service.product.ProductMessage;
import monto.service.product.ProductMessages;

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
                        return Optional.of((ProductMessage) ProductMessages.decode(msg));
                    } catch (Exception e) {
                        return Optional.empty();
                    }
                });
    }

    public void close() throws Exception {
        connection.close();
    }
}
