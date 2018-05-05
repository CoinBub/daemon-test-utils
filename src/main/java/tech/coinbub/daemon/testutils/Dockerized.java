package tech.coinbub.daemon.testutils;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.googlecode.jsonrpc4j.IJsonRpcClient;
import com.googlecode.jsonrpc4j.JsonRpcClient;
import com.googlecode.jsonrpc4j.JsonRpcHttpClient;
import com.googlecode.jsonrpc4j.ProxyUtil;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
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

    private static Properties props;
    private static String image;
    private static String portStr;
    private static int portNum;
    private static String rpcuser;
    private static String rpcpass;
    private static String name;
    private static String[] cmd;
    private static String confPath;
    private static boolean persistent = false;

    private static DockerClient docker;
    private static String containerId;
    private static int hostPort = -1;
    private static JsonRpcClient rpcClient;
    private static Class<?> clientClass;
    private static Object client;

    @Override
    public void beforeAll(final ExtensionContext context) throws Exception {
        if (client != null) {
            return;
        }

        getParameters();

        docker = DockerClientBuilder.getInstance().build();

        pullImage();
        createContainer();
        copyConfiguration();
        startContainer();
        getHostPortBinding();

        final URL url = new URL("http://" + System.getProperty("DOCKER_HOST", "localhost") + ":" + hostPort);
        LOGGER.info("Using URL {}", url.toString());
        rpcClient = new JsonRpcHttpClient(url, Util.headers(rpcuser, rpcpass));
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

    private void getParameters() throws IOException, ClassNotFoundException {
        if (props != null) {
            return;
        }

        props = new Properties();
        try (InputStream is = Dockerized.class.getResourceAsStream("/docker.properties")) {
            if (is == null) {
                throw new RuntimeException("Unable to load docker properties. Make sure `docker.properties` exists in src/test/resources");
            }
            props.load(is);
        }

        image = props.getProperty("image");
        portStr = props.getProperty("port");
        portNum = Integer.parseInt(portStr);
        rpcuser = props.getProperty("rpcuser", "user");
        rpcpass = props.getProperty("rpcpass", "pass");
        name = props.getProperty("name");
        if (props.contains("cmd")) {
            cmd = props.getProperty("cmd").split(" ");
        }
        confPath = props.getProperty("conf");
        clientClass = Class.forName(props.getProperty("class"));
        persistent = Boolean.parseBoolean(props.getProperty("persistent", "false"));
    }

    /**
     * Pulls down the requested image if it doesn't already exist locally.
     * @throws InterruptedException 
     */
    private void pullImage() throws InterruptedException {
        final List<Image> img = docker.listImagesCmd()
                .withImageNameFilter(image)
                .exec();
        if (img.isEmpty()) {
            docker.pullImageCmd(image)
                    .exec(new PullImageResultCallback())
                    .awaitCompletion();
        }
    }

    /**
     * Create the container if it doesn't already exist
     */
    private void createContainer() {
        
        try {
            final CreateContainerCmd result = docker.createContainerCmd(image)
                    .withStdInOnce(false)
                    .withStdinOpen(false)
                    .withPortSpecs(portStr)
                    .withExposedPorts(ExposedPort.tcp(portNum))
                    .withPortBindings(new PortBinding(Ports.Binding.bindIp("0.0.0.0"), ExposedPort.tcp(portNum)));
            if (name != null) {
                result.withName(name);
            }
            if (cmd != null) {
                result.withCmd(cmd);
            }
            containerId = result.exec()
                    .getId();
            LOGGER.info("Started container {}", containerId);
        } catch (ConflictException ex) {
            containerId = docker.inspectContainerCmd(name)
                    .exec()
                    .getId();
            LOGGER.info("Container {} already exists with id {}", name, containerId);
        }
    }

    private void copyConfiguration() throws IOException {
        try (InputStream stream = this.getClass().getResourceAsStream("/conf.tar.gz")) {
            if (stream == null) {
                LOGGER.warn("Could not retrieve conf.tar.gz. Ensure it exists in src/test/resources");
                return;
            }
            docker.copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(stream)
                    .withRemotePath(confPath)
                    .exec();
        }
    }

    private void startContainer() {
        try {
            docker.startContainerCmd(containerId)
                    .exec();
            LOGGER.info("Container {} started", containerId);
        } catch (NotModifiedException ex) {
            LOGGER.info("Container {} already started", containerId);
        }
    }

    private void getHostPortBinding() {
        Map<ExposedPort, Ports.Binding[]> bindings = docker.inspectContainerCmd(containerId)
                .exec()
                .getNetworkSettings()
                .getPorts()
                .getBindings();
        for (Map.Entry<ExposedPort, Ports.Binding[]> port : bindings.entrySet()) {
            if (port.getKey().getPort() == portNum) {
                if (port.getValue() == null || port.getValue().length != 1) {
                    throw new RuntimeException("Found " + port.getValue().length + " bound ports. Expected 1");
                }
                hostPort = Integer.parseInt(port.getValue()[0].getHostPortSpec());
            }
        }

        if (hostPort < 0) {
            throw new RuntimeException("RPC port " + portNum + " not bound to host");
        }
        LOGGER.info("RPC port {} bound to {}", portNum, hostPort);
    }
}
