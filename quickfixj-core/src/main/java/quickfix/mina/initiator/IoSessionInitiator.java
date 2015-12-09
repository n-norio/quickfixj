/*******************************************************************************
 * Copyright (c) quickfixengine.org  All rights reserved.
 *
 * This file is part of the QuickFIX FIX Engine
 *
 * This file may be distributed under the terms of the quickfixengine.org
 * license as defined by quickfixengine.org and appearing in the file
 * LICENSE included in the packaging of this file.
 *
 * This file is provided AS IS with NO WARRANTY OF ANY KIND, INCLUDING
 * THE WARRANTY OF DESIGN, MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE.
 *
 * See http://www.quickfixengine.org/LICENSE for licensing information.
 *
 * Contact ask@quickfixengine.org if any conditions of this licensing
 * are not clear to you.
 ******************************************************************************/

package quickfix.mina.initiator;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import org.apache.mina.core.filterchain.IoFilterChainBuilder;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.service.IoConnector;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.ConfigError;
import quickfix.LogUtil;
import quickfix.Session;
import quickfix.SystemTime;
import quickfix.mina.CompositeIoFilterChainBuilder;
import quickfix.mina.EventHandlingStrategy;
import quickfix.mina.NetworkingOptions;
import quickfix.mina.ProtocolFactory;
import quickfix.mina.message.FIXProtocolCodecFactory;
import quickfix.mina.ssl.SSLConfig;
import quickfix.mina.ssl.SSLContextFactory;
import quickfix.mina.ssl.SSLFilter;
import quickfix.mina.ssl.SSLSupport;

public class IoSessionInitiator {
    private final static long CONNECT_POLL_TIMEOUT = 2000L;
    private final ScheduledExecutorService executor;
    private final ConnectTask reconnectTask;

    private Future<?> reconnectFuture;
    protected final static Logger log = LoggerFactory.getLogger("display." + IoSessionInitiator.class.getName());

    public IoSessionInitiator(Session fixSession, SocketAddress[] socketAddresses, SocketAddress localAddress,
            int[] reconnectIntervalInSeconds, ScheduledExecutorService executor,
            NetworkingOptions networkingOptions, EventHandlingStrategy eventHandlingStrategy,
            IoFilterChainBuilder userIoFilterChainBuilder, boolean sslEnabled, SSLConfig sslConfig) throws ConfigError {
        this.executor = executor;
        final long[] reconnectIntervalInMillis = new long[reconnectIntervalInSeconds.length];
        for (int ii = 0; ii != reconnectIntervalInSeconds.length; ++ii) {
            reconnectIntervalInMillis[ii] = reconnectIntervalInSeconds[ii] * 1000L;
        }
        try {
            reconnectTask = new ConnectTask(sslEnabled, socketAddresses, localAddress, userIoFilterChainBuilder,
                    fixSession, reconnectIntervalInMillis, networkingOptions,
                    eventHandlingStrategy, sslConfig);
        } catch (GeneralSecurityException e) {
            throw new ConfigError(e);
        }
        log.info("[" + fixSession.getSessionID() + "] " + Arrays.asList(socketAddresses));
    }

    private static class ConnectTask implements Runnable {
        private final SocketAddress[] socketAddresses;
        private final SocketAddress localAddress;
        private final IoConnector ioConnector;
        private final Session fixSession;
        private final long[] reconnectIntervalInMillis;
        private final SSLConfig sslConfig;
        private final InitiatorIoHandler ioHandler;

        private IoSession ioSession;
        private long lastReconnectAttemptTime;
        private long lastConnectTime;
        private int nextSocketAddressIndex;
        private int connectionFailureCount;
        private ConnectFuture connectFuture;

        public ConnectTask(boolean sslEnabled, SocketAddress[] socketAddresses,
                SocketAddress localAddress, IoFilterChainBuilder userIoFilterChainBuilder, Session fixSession,
                long[] reconnectIntervalInMillis, NetworkingOptions networkingOptions,
                EventHandlingStrategy eventHandlingStrategy, SSLConfig sslConfig) throws ConfigError, GeneralSecurityException {
            this.socketAddresses = socketAddresses;
            this.localAddress = localAddress;
            this.fixSession = fixSession;
            this.reconnectIntervalInMillis = reconnectIntervalInMillis;
            this.sslConfig = sslConfig;
            ioConnector = ProtocolFactory.createIoConnector(socketAddresses[0]);
            CompositeIoFilterChainBuilder ioFilterChainBuilder = new CompositeIoFilterChainBuilder(
                    userIoFilterChainBuilder);

            if (sslEnabled) {
                installSslFilter(ioFilterChainBuilder);
            }

            ioFilterChainBuilder.addLast(FIXProtocolCodecFactory.FILTER_NAME,
                    new ProtocolCodecFilter(new FIXProtocolCodecFactory()));

            ioConnector.setFilterChainBuilder(ioFilterChainBuilder);
            ioHandler = new InitiatorIoHandler(fixSession, networkingOptions,
                    eventHandlingStrategy);
        }

        private void installSslFilter(CompositeIoFilterChainBuilder ioFilterChainBuilder)
                throws GeneralSecurityException {
            SSLContext sslContext = SSLContextFactory.getInstance(sslConfig);
            SSLFilter sslFilter = new SSLFilter(sslContext);
            sslFilter.setUseClientMode(true);
            sslFilter.setCipherSuites(sslConfig.getEnabledCipherSuites() != null ? sslConfig.getEnabledCipherSuites()
                    : SSLSupport.getDefaultCipherSuites(sslContext));
            sslFilter.setEnabledProtocols(sslConfig.getEnabledProtocols() != null ? sslConfig.getEnabledProtocols()
                    : SSLSupport.getSupportedProtocols(sslContext));
            ioFilterChainBuilder.addLast(SSLSupport.FILTER_NAME, sslFilter);
        }

        public synchronized void run() {
            if (connectFuture == null) {
                if (shouldReconnect()) {
                    connect();
                }
            } else {
                pollConnectFuture();
            }
        }

        private void connect() {
            lastReconnectAttemptTime = SystemTime.currentTimeMillis();
            SocketAddress nextSocketAddress = getNextSocketAddress();
            ioConnector.setHandler(ioHandler);
            try {
                if (localAddress == null) {
                    connectFuture = ioConnector.connect(nextSocketAddress);
                } else {
                    // QFJ-482
                    connectFuture = ioConnector.connect(nextSocketAddress, localAddress);
                }
                pollConnectFuture();
            } catch (Throwable e) {
                handleConnectException(e);
            }
        }

        private void pollConnectFuture() {
            try {
                connectFuture.awaitUninterruptibly(CONNECT_POLL_TIMEOUT);
                if (connectFuture.getSession() != null) {
                    ioSession = connectFuture.getSession();
                    connectionFailureCount = 0;
                    lastConnectTime = System.currentTimeMillis();
                    connectFuture = null;
                } else {
                    fixSession.getLog().onEvent(
                            "Pending connection not established after "
                                    + (System.currentTimeMillis() - lastReconnectAttemptTime)
                                    + " ms.");
                }
            } catch (Throwable e) {
                handleConnectException(e);
            }
        }

        private void handleConnectException(Throwable e) {
            ++connectionFailureCount;
            SocketAddress socketAddress = socketAddresses[getCurrentSocketAddressIndex()];
            unresolveCurrentSocketAddress(socketAddress);
            while (e.getCause() != null) {
                e = e.getCause();
            }
            final String nextRetryMsg = " (Next retry in " + computeNextRetryConnectDelay() + " milliseconds)";
            if (e instanceof IOException) {
                fixSession.getLog().onErrorEvent(e.getClass().getName() + " during connection to " + socketAddress + ": " + e + nextRetryMsg);
            } else {
                LogUtil.logThrowable(fixSession.getLog(), "Exception during connection to " + socketAddress + nextRetryMsg, e);
            }
            connectFuture = null;
        }

        private SocketAddress getNextSocketAddress() {
            SocketAddress socketAddress = socketAddresses[nextSocketAddressIndex];

            // QFJ-266 Recreate socket address for unresolved addresses
            if (socketAddress instanceof InetSocketAddress) {
                InetSocketAddress inetAddr = (InetSocketAddress) socketAddress;
                if (inetAddr.isUnresolved()) {
                    socketAddress = new InetSocketAddress(inetAddr.getHostName(), inetAddr
                            .getPort());
                    socketAddresses[nextSocketAddressIndex] = socketAddress;
                }
            }
            nextSocketAddressIndex = (nextSocketAddressIndex + 1) % socketAddresses.length;
            return socketAddress;
        }

        // QFJ-822 Reset cached DNS resolution information on connection failure.
        private void unresolveCurrentSocketAddress(SocketAddress socketAddress) {
            if (socketAddress instanceof InetSocketAddress) {
                InetSocketAddress inetAddr = (InetSocketAddress) socketAddress;
                socketAddresses[getCurrentSocketAddressIndex()] = InetSocketAddress.createUnresolved(
                    inetAddr.getHostName(), inetAddr.getPort());
            }
        }

        private int getCurrentSocketAddressIndex() {
            int currentSocketAddressIndex = (nextSocketAddressIndex + socketAddresses.length - 1) % socketAddresses.length;
            return currentSocketAddressIndex;
        }

        private boolean shouldReconnect() {
            return (ioSession == null || !ioSession.isConnected()) && isTimeForReconnect()
                    && (fixSession.isEnabled() && fixSession.isSessionTime());
        }

        private long computeNextRetryConnectDelay() {
            int index = connectionFailureCount - 1;
            if (index < 0)
                index = 0;
            long millis;
            if (index >= reconnectIntervalInMillis.length) {
                millis = reconnectIntervalInMillis[reconnectIntervalInMillis.length - 1];
            } else {
                millis = reconnectIntervalInMillis[index];
            }
            return millis;
        }

        private boolean isTimeForReconnect() {
            return SystemTime.currentTimeMillis() - lastReconnectAttemptTime >= computeNextRetryConnectDelay();
        }

        // TODO JMX Expose reconnect property

        @SuppressWarnings("unused") // exposed via JMX
        public synchronized int getConnectionFailureCount() {
            return connectionFailureCount;
        }

        @SuppressWarnings("unused") // exposed via JMX
        public synchronized long getLastReconnectAttemptTime() {
            return lastReconnectAttemptTime;
        }

        @SuppressWarnings("unused") // exposed via JMX
        public synchronized long getLastConnectTime() {
            return lastConnectTime;
        }

        public Session getFixSession() {
            return fixSession;
        }
    }

    synchronized void start() {
        if (reconnectFuture == null) {
            // The following logon reenabled the session. The actual logon will take
            // place as a side-effect of the session timer task (not the reconnect task).
            reconnectTask.getFixSession().logon(); // only enables the session
            reconnectFuture = executor
                    .scheduleWithFixedDelay(reconnectTask, 0, 1, TimeUnit.SECONDS);
        }
    }

    synchronized void stop() {
        if (reconnectFuture != null) {
            reconnectFuture.cancel(true);
            reconnectFuture = null;
        }
        // QFJ-849: clean up resources of MINA connector
        reconnectTask.ioConnector.dispose();
    }
}
