package com.lynra.kafkatower.kafka;

import com.lynra.kafkatower.model.KafkaSettings;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.errors.TimeoutException;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Checks a cluster against the real brokers before it is saved: the certificate files are
 * readable, the brokers answer, and the TLS / SASL settings are accepted.
 */
@Component
public class KafkaClusterVerifier {

    private static final long WAIT_SECONDS = 12;

    /**
     * @return the number of brokers that answered
     * @throws IllegalArgumentException with a user-facing reason when the cluster does not work
     */
    public int verify(KafkaSettings.Cluster cluster) {
        checkFiles(cluster);
        AdminClient admin = null;
        try {
            Map<String, Object> config = KafkaAdminConfig.clientConfig(
                    KafkaClusterSettingsMapper.toClusterConfig(cluster));
            admin = AdminClient.create(config);
            return admin.describeCluster().nodes().get(WAIT_SECONDS, TimeUnit.SECONDS).size();
        } catch (ExecutionException e) {
            throw new IllegalArgumentException(describe(e.getCause() != null ? e.getCause() : e));
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalArgumentException("No answer from " + cluster.bootstrapServers() + " within "
                    + WAIT_SECONDS + "s. Check the address, and that the security type matches the listener.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("The check was interrupted.");
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(describe(e));
        } finally {
            if (admin != null) admin.close(Duration.ofSeconds(2));
        }
    }

    private static void checkFiles(KafkaSettings.Cluster c) {
        if (!c.usesTls() || c.ssl() == null) {
            if (KafkaSettings.MTLS.equals(c.security())) {
                throw new IllegalArgumentException("mTLS needs a client keystore.");
            }
            return;
        }
        KafkaSettings.Ssl ssl = c.ssl();
        if (KafkaSettings.MTLS.equals(c.security())) {
            requireReadable("Keystore", ssl.keystoreLocation(), true);
        }
        requireReadable("Truststore", ssl.truststoreLocation(), false);
    }

    private static void requireReadable(String what, String path, boolean required) {
        if (path == null || path.isBlank()) {
            if (required) throw new IllegalArgumentException(what + " path is required.");
            return;
        }
        File file = new File(path);
        if (!file.isFile()) {
            throw new IllegalArgumentException(what + " file not found on the server: " + path);
        }
        if (!file.canRead()) {
            throw new IllegalArgumentException(what + " file is not readable by the application: " + path);
        }
    }

    private static String describe(Throwable e) {
        if (e instanceof TimeoutException) {
            return "Timed out talking to the brokers. Check the address and that the security type matches the listener.";
        }
        String message = e.getMessage();
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null && (message == null || !message.contains(cause.getMessage()))) {
            message = (message == null ? "" : message + ": ") + cause.getMessage();
        }
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
