package io.tranto.webserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Tranto web server: REST API + (later) the Vue UI. Spring Web MVC on virtual threads.
 * Start with {@code java -jar tranto-server.jar} and hit {@code http://localhost:8080}.
 */
@SpringBootApplication
public class TrantoServer {

    public static void main(final String[] args) {
        SpringApplication.run(TrantoServer.class, args);
    }
}
