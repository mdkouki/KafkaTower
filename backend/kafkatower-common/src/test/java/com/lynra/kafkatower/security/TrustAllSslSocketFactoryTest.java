package com.lynra.kafkatower.security;

import org.junit.jupiter.api.Test;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

import static org.assertj.core.api.Assertions.assertThat;

class TrustAllSslSocketFactoryTest {

    @Test
    void constructsSuccessfullyAndDelegatesCipherSuites() {
        TrustAllSslSocketFactory factory = new TrustAllSslSocketFactory();

        assertThat(factory.getDefaultCipherSuites()).isNotEmpty();
        assertThat(factory.getSupportedCipherSuites()).isNotEmpty();
    }

    @Test
    void getDefaultReturnsATrustAllSslSocketFactoryInstance() {
        SocketFactory factory = TrustAllSslSocketFactory.getDefault();

        assertThat(factory).isInstanceOf(TrustAllSslSocketFactory.class);
        assertThat(factory).isInstanceOf(SSLSocketFactory.class);
    }

    @Test
    void eachCallToGetDefaultReturnsANewInstance() {
        SocketFactory first = TrustAllSslSocketFactory.getDefault();
        SocketFactory second = TrustAllSslSocketFactory.getDefault();

        assertThat(first).isNotSameAs(second);
    }
}
