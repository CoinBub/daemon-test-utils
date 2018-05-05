package tech.coinbub.daemon.testutils;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.googlecode.jsonrpc4j.IJsonRpcClient;
import com.googlecode.jsonrpc4j.JsonRpcClient;
import com.googlecode.jsonrpc4j.JsonRpcHttpClient;
import com.googlecode.jsonrpc4j.ProxyUtil;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Dockerized implements BeforeAllCallback, ParameterResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(Dockerized.class);
    private static final int RPC_PORT = 10001;
    private static DockerClient docker;
    private static String containerId;
    private static int hostPort = -1;
    private static JsonRpcClient rpcClient;
    private static Class<?> clientClass;
    private static Object client;
    private static boolean persistent = false;

    @Override
    public void beforeAll(final ExtensionContext context) throws Exception {
        if (client != null) {
            return;
        }

        final Properties props = new Properties();
        try (InputStream is = this.getClass().getResourceAsStream("/docker.properties")) {
            if (is == null) {
                throw new RuntimeException("Unable to load docker properties. Make sure `docker.properties` exists in src/test/resources");
            }
            props.load(is);
        }

        final String image = props.getProperty("image");
        final String portStr = props.getProperty("port");
        final int portNum = Integer.parseInt(portStr);
        final String rpcuser = props.getProperty("rpcuser", "user");
        final String rpcpass = props.getProperty("rpcpass", "pass");
        final String name = props.getProperty("name");
        final String[] cmd = props.getProperty("cmd").split(" ");
        final String confPath = props.getProperty("conf");
        clientClass = Class.forName(props.getProperty("class"));
        persistent = Boolean.parseBoolean(props.getProperty("persistent", "false"));


        docker = DockerClientBuilder.getInstance().build();

        // Pull image
        docker.pullImageCmd(image)
                .exec(new PullImageResultCallback())
                .awaitCompletion();
        try {
            containerId = docker.createContainerCmd(image)
                    .withStdInOnce(false)
                    .withStdinOpen(false)
                    .withPortSpecs(portStr)
                    .withExposedPorts(ExposedPort.tcp(portNum))
                    .withPortBindings(new PortBinding(Ports.Binding.bindIp("0.0.0.0"), ExposedPort.tcp(portNum)))
                    .withName(name)
                    .withCmd(cmd)
                    .exec()
                    .getId();
            LOGGER.info("Started container {}", containerId);
        } catch (ConflictException ex) {
            containerId = docker.inspectContainerCmd(name)
                    .exec()
                    .getId();
            LOGGER.info("Container {} already exists with id {}", name, containerId);
        }

        try (InputStream stream = this.getClass().getResourceAsStream("/conf.tar.gz")) {
            if (stream == null) {
                throw new IOException("Could not retrieve conf.tar.gz");
            }
            docker.copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(stream)
                    .withRemotePath(confPath)
                    .exec();

            try {
                docker.startContainerCmd(containerId)
                        .exec();
                LOGGER.info("Container {} started", containerId);
            } catch (NotModifiedException ex) {
                LOGGER.info("Container {} already started", containerId);
            }

            Map<ExposedPort, Ports.Binding[]> bindings = docker.inspectContainerCmd(containerId)
                    .exec()
                    .getNetworkSettings()
                    .getPorts()
                    .getBindings();
            for (Map.Entry<ExposedPort, Ports.Binding[]> port : bindings.entrySet()) {
                if (port.getKey().getPort() == 10001) {
                    if (port.getValue() == null || port.getValue().length != 1) {
                        throw new RuntimeException("Found " + port.getValue().length + " bound ports. Expected 1");
                    }
                    hostPort = Integer.parseInt(port.getValue()[0].getHostPortSpec());
                }
            }

            if (hostPort < 0) {
                throw new RuntimeException("RPC port " + RPC_PORT + " not bound to host");
            }
            LOGGER.info("RPC port " + RPC_PORT + " bound to " + hostPort);
        }

        rpcClient = new JsonRpcHttpClient(new URL("http://localhost:" + hostPort), Util.headers(rpcuser, rpcpass));
        client = ProxyUtil.createClientProxy(
                this.getClass().getClassLoader(),
                clientClass,
                (IJsonRpcClient) rpcClient);
        Thread.sleep(2000l);

        Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> {
                    if (!persistent) {
                        docker.stopContainerCmd(containerId).exec();
                        docker.removeContainerCmd(containerId).exec();
                        LOGGER.info("Stopped and removed container {}", containerId);
                    } else {
                        LOGGER.info("Left container {} alive", containerId);
                    }
                }));
    }

    @Override
    public boolean supportsParameter(final ParameterContext parameterContext,
            final ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return parameterContext.getParameter().getType().equals(clientClass);
    }

    @Override
    public Object resolveParameter(final ParameterContext parameterContext,
            final ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return client;
    }
}
