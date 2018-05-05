package tech.coinbub.daemon.testutils;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tech.coinbub.daemon.support.TestServer;

@ExtendWith(Dockerized.class)
public class DockerizedIT {
    @Test
    public void basicRunTest(final TestServer server) {
        assertThat(server.add(1, 2), is(equalTo(3)));
    }
}
