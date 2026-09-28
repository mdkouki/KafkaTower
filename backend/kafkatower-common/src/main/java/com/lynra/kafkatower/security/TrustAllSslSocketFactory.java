package com.lynra.kafkatower.security;

import javax.net.SocketFactory;
import javax.net.ssl.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

public class TrustAllSslSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;

    public TrustAllSslSocketFactory() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            this.delegate = ctx.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize TrustAllSslSocketFactory", e);
        }
    }

    // JNDI resolves the factory via this static method
    public static SocketFactory getDefault() {
        return new TrustAllSslSocketFactory();
    }

    @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
    @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException { return delegate.createSocket(s, host, port, autoClose); }
    @Override public Socket createSocket(String host, int port) throws IOException { return delegate.createSocket(host, port); }
    @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException { return delegate.createSocket(host, port, localHost, localPort); }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException { return delegate.createSocket(host, port); }
    @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException { return delegate.createSocket(address, port, localAddress, localPort); }
}
