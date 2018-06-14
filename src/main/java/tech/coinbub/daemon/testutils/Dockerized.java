package tech.coinbub.daemon.testutils;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.NetworkSettings;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.googlecode.jsonrpc4j.IJsonRpcClient;
import com.googlecode.jsonrpc4j.JsonRpcClient;
import com.googlecode.jsonrpc4j.JsonRpcHttpClient;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import org.apache.commons.lang.StringUtils;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.coinbub.daemon.proxy.ProxyUtil;

public class Dockerized implements BeforeAllCallback, BeforeEachCallback, ParameterResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(Dockerized.class);

    private static Properties props;
    private static String image;
    private static String portStr;
    private static int portNum;
    private static String host = "localhost";
    private static String rpcuser;
    private static String rpcpass;
    private static String name = "dockerized-test";
    private static String[] cmd;
    private static String confPath;
    private static boolean persistent = false;

    private static DockerClient docker;
    private static String containerId;
    private static int hostPort = -1;
    private static JsonRpcClient rpcClient;
    private static Class<?> clientClass;
    private static Class<?> normalizedClass;
    private static Object client;
    private static Object normalized;

    @Override
    public void beforeAll(final ExtensionContext context) throws Exception {
        if (client != null) {
            return;
        }

        getParameters();

        docker = DockerClientBuilder.getInstance().build();

        if (System.getProperty("containerLocation") == null) {
            pullImage();
            createContainer();
            copyConfiguration();
            startContainer();
            getHostPortBinding();
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

        final URL url = new URL("http://" + System.getProperty("containerHost", host) + ":" + System.getProperty("containerPort", Integer.toString(hostPort)));
        LOGGER.info("Using URL {}", url.toString());
        rpcClient = new JsonRpcHttpClient(url, Util.headers(rpcuser, rpcpass));
        client = ProxyUtil.createClientProxy(
                this.getClass().getClassLoader(),
                clientClass,
                (IJsonRpcClient) rpcClient);
        if (normalizedClass != null) {
            normalized = normalizedClass.getConstructor(clientClass).newInstance(client);
        }
        Thread.sleep(2000l);
    }

    @Override
    public void beforeEach(final ExtensionContext context) throws Exception {
        context.getStore(ExtensionContext.Namespace.GLOBAL).put(Dockerized.class, client);
    }

    @Override
    public boolean supportsParameter(final ParameterContext parameterContext,
            final ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return parameterContext.getParameter().getType().equals(clientClass)
                || parameterContext.getParameter().getType().equals(normalizedClass);
    }

    @Override
    public Object resolveParameter(final ParameterContext parameterContext,
            final ExtensionContext extensionContext)
            throws ParameterResolutionException {
        if (parameterContext.getParameter().getType().equals(clientClass)) {
            return client;
        }
        return normalized;
    }
    
    /**
     * [code borrowed from ant.jar]
     * Crack a command line.
     * @param cmd the command line to process.
     * @return the command line broken into strings.
     * An empty or null toProcess parameter results in a zero sized array.
     */
    private static String[] parseCommand(final String cmd) {
        if (cmd == null || cmd.length() == 0) {
            //no command? no string
            return new String[0];
        }
        // parse with a simple finite state machine

        final int normal = 0;
        final int inQuote = 1;
        final int inDoubleQuote = 2;
        int state = normal;
        final StringTokenizer tok = new StringTokenizer(cmd, "\"\' ", true);
        final ArrayList<String> result = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        boolean lastTokenHasBeenQuoted = false;

        while (tok.hasMoreTokens()) {
            String nextTok = tok.nextToken();
            switch (state) {
            case inQuote:
                if ("\'".equals(nextTok)) {
                    lastTokenHasBeenQuoted = true;
                    state = normal;
                } else {
                    current.append(nextTok);
                }
                break;
            case inDoubleQuote:
                if ("\"".equals(nextTok)) {
                    lastTokenHasBeenQuoted = true;
                    state = normal;
                } else {
                    current.append(nextTok);
                }
                break;
            default:
                if ("\'".equals(nextTok)) {
                    state = inQuote;
                } else if ("\"".equals(nextTok)) {
                    state = inDoubleQuote;
                } else if (" ".equals(nextTok)) {
                    if (lastTokenHasBeenQuoted || current.length() != 0) {
                        result.add(current.toString());
                        current.setLength(0);
                    }
                } else {
                    current.append(nextTok);
                }
                lastTokenHasBeenQuoted = false;
                break;
            }
        }
        if (lastTokenHasBeenQuoted || current.length() != 0) {
            result.add(current.toString());
        }
        if (state == inQuote || state == inDoubleQuote) {
            throw new RuntimeException("unbalanced quotes in " + cmd);
        }
        return result.toArray(new String[result.size()]);
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
        name = props.getProperty("name", name);
        if (props.containsKey("cmd")) {
            cmd = parseCommand(props.getProperty("cmd"));
            LOGGER.info("Command: {}", cmd);
        }
        confPath = props.getProperty("conf");
        clientClass = Class.forName(props.getProperty("class"));
        if (props.containsKey("normalized")) {
            normalizedClass = Class.forName(props.getProperty("normalized"));
        }
        persistent = Boolean.parseBoolean(props.getProperty("persistent", "false"));
    }

    /**
     * Pulls down the requested image if it doesn't already exist locally.
     *
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
            final List<Container> containers = docker.listContainersCmd()
                    .withShowAll(true)
                    .exec();
            containerId = docker.inspectContainerCmd(name)
                    .exec()
                    .getId();
            LOGGER.info("Container {} already exists with id {}", name, containerId);
            return;
        } catch (NotFoundException ex) {}

        final CreateContainerCmd result = docker.createContainerCmd(image)
                .withStdInOnce(false)
                .withStdinOpen(false)
                .withPortSpecs(portStr)
                .withExposedPorts(ExposedPort.tcp(portNum))
                .withPortBindings(new PortBinding(Ports.Binding.bindIp("0.0.0.0"), ExposedPort.tcp(portNum)))
                .withName(name);
        if (cmd != null) {
            result.withCmd(cmd);
        }
        containerId = result.exec()
                .getId();
        LOGGER.info("Built container {}", containerId);
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
            LOGGER.info("Started container {}", containerId);
        } catch (NotModifiedException ex) {
            LOGGER.info("Container {} already running", containerId);
        }
    }

    private void getHostPortBinding() throws IOException {
        final NetworkSettings network = docker.inspectContainerCmd(containerId)
                .exec()
                .getNetworkSettings();

        // Grab the host of the docker container
        if (System.getProperty("dockerizedByIP", "false").equals("true")) {
            LOGGER.debug("Connecting to container by IP");
            for (Map.Entry<String, ContainerNetwork> net : network.getNetworks().entrySet()) {
                LOGGER.debug("Network {} IP {}", net.getValue().getNetworkID(), net.getValue().getIpAddress());
                host = net.getValue().getIpAddress();
            }
            LOGGER.info("Using host {}", host);
        } else if (System.getProperty("dockerizedHost") != null) {
            String urlStr = System.getProperty("dockerizedHost");
            if (urlStr.contains("://")) {
                urlStr = urlStr.split("://")[1];
            }
            final URL netUrl = new URL("http://" + urlStr);
            host = netUrl.getHost();
        }

        if (System.getProperty("dockerizedUseContainerPort", "false").equals("true")) {
            hostPort = portNum;
            LOGGER.info("Using container port {}", hostPort);
            return;
        }

        // Otherwise, grab the port bound to the exposed docker port
        final Map<ExposedPort, Ports.Binding[]> bindings = network.getPorts()
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
