/*
 * Copyright 2011- Per Wendel
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package spark.embeddedserver.jetty;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import spark.embeddedserver.EmbeddedServer;
import spark.embeddedserver.jetty.websocket.WebSocketHandlerWrapper;
import spark.embeddedserver.jetty.websocket.WebSocketServletContextHandlerFactory;
import spark.ssl.SslStores;

/**
 * Spark server implementation
 *
 * @author Per Wendel
 */
public class EmbeddedJettyServer implements EmbeddedServer {

    private static final int SPARK_DEFAULT_PORT = 4567;
    private static final String NAME = "Spark";

    private Server server;

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private Map<String, WebSocketHandlerWrapper> webSocketHandlers;
    private Optional<Integer> webSocketIdleTimeoutMillis;
    private List<Handler> handlers = new ArrayList<>();
    private StatisticsHandler statisticsHandler;

    public EmbeddedJettyServer(Handler sparkHandler) {
        this.handlers.add(sparkHandler);
    }

    @Override
    public void configureWebSockets(Map<String, WebSocketHandlerWrapper> webSocketHandlers,
                                    Optional<Integer> webSocketIdleTimeoutMillis) {

        this.webSocketHandlers = webSocketHandlers;
        this.webSocketIdleTimeoutMillis = webSocketIdleTimeoutMillis;
    }

    @Override
    public void configureAdditionalHandlers(List<Handler> additionalHandlers) {
        if (additionalHandlers != null) {
            this.handlers.addAll(0, additionalHandlers);
        }
    }

    @Override
    public void configureStatisticsHandler(StatisticsHandler statisticsHandler) {
        if (statisticsHandler != null) {
            this.statisticsHandler = statisticsHandler;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int ignite(String host,
                      int port,
                      SslStores sslStores,
                      CountDownLatch latch,
                      int maxThreads,
                      int minThreads,
                      int threadIdleTimeoutMillis) {

        if (port == 0) {
            try (ServerSocket s = new ServerSocket(0)) {
                port = s.getLocalPort();
            } catch (IOException e) {
                logger.error("Could not get first available port (port set to 0), using default: {}", SPARK_DEFAULT_PORT);
                port = SPARK_DEFAULT_PORT;
            }
        }

        server = JettyServer.create(maxThreads, minThreads, threadIdleTimeoutMillis);

        ServerConnector connector;

        if (sslStores == null) {
            connector = SocketConnectorFactory.createSocketConnector(server, host, port);
        } else {
            connector = SocketConnectorFactory.createSecureSocketConnector(server, host, port, sslStores);
        }

        server = connector.getServer();
        server.setConnectors(new Connector[] {connector});

        Handler handler = initHandler();

        server.setHandler(handler);

        try {
            logger.info("== {} has ignited ...", NAME);
            logger.info(">> Listening on {}:{}", host, port);

            server.start();
            latch.countDown();
            server.join();
        } catch (Exception e) {
            logger.error("ignite failed", e);
            System.exit(100);
        }

        return port;
    }

    private Handler initHandler() {

        ServletContextHandler webSocketServletContextHandler =
                WebSocketServletContextHandlerFactory.create(webSocketHandlers, webSocketIdleTimeoutMillis);

        if (webSocketServletContextHandler != null) {
            handlers.add(webSocketServletContextHandler);
        }

        Handler handler;

        if (handlers.size() < 2) {
            // use single handler as jetty handler
            handler = handlers.get(0);
        } else {
            // use handler list as jetty handler
            HandlerList handlers = new HandlerList();
            handlers.setHandlers(this.handlers.toArray(new Handler[this.handlers.size()]));
            handler = handlers;
        }

        if (statisticsHandler != null) {
            // wrap handler handler with statistics handler
            statisticsHandler.setHandler(handler);
            handler = statisticsHandler;
        }

        return handler;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void extinguish() {
        logger.info(">>> {} shutting down ...", NAME);
        try {
            if (server != null) {
                server.stop();
            }
        } catch (Exception e) {
            logger.error("stop failed", e);
            System.exit(100); // NOSONAR
        }
        logger.info("done");
    }


}
