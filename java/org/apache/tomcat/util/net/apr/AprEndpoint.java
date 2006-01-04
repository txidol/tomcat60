/*
 *  Copyright 2005 The Apache Software Foundation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.tomcat.util.net.apr;

import java.util.Stack;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tomcat.jni.Address;
import org.apache.tomcat.jni.Error;
import org.apache.tomcat.jni.Library;
import org.apache.tomcat.jni.OS;
import org.apache.tomcat.jni.Poll;
import org.apache.tomcat.jni.Pool;
import org.apache.tomcat.jni.SSL;
import org.apache.tomcat.jni.SSLContext;
import org.apache.tomcat.jni.SSLSocket;
import org.apache.tomcat.jni.Socket;
import org.apache.tomcat.jni.Status;
import org.apache.tomcat.util.net.PoolTcpEndpoint;
import org.apache.tomcat.util.net.TcpConnection;
import org.apache.tomcat.util.net.TcpConnectionHandler;
import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.util.threads.ThreadWithAttributes;

/**
 * APR tailored thread pool, providing the following services:
 * <ul>
 * <li>Socket acceptor thread</li>
 * <li>Socket poller thread</li>
 * <li>Sendfile thread</li>
 * <li>Worker threads pool</li>
 * </ul>
 *
 * When switching to Java 5, there's an opportunity to use the virtual
 * machine's thread pool.
 *
 * @author Mladen Turk
 * @author Remy Maucherat
 */
public class AprEndpoint extends PoolTcpEndpoint {


    // -------------------------------------------------------------- Constants


    protected static Log log = LogFactory.getLog(AprEndpoint.class);

    protected static StringManager sm =
        StringManager.getManager("org.apache.tomcat.util.net.res");


    // where is this used ?
    /**
     * The Request attribute key for the cipher suite.
     */
    public static final String CIPHER_SUITE_KEY = "javax.servlet.request.cipher_suite";

    /**
     * The Request attribute key for the key size.
     */
    public static final String KEY_SIZE_KEY = "javax.servlet.request.key_size";

    /**
     * The Request attribute key for the client certificate chain.
     */
    public static final String CERTIFICATE_KEY = "javax.servlet.request.X509Certificate";

    /**
     * The Request attribute key for the session id.
     * This one is a Tomcat extension to the Servlet spec.
     */
    public static final String SESSION_ID_KEY = "javax.servlet.request.ssl_session";


    // ----------------------------------------------------------------- Fields


    /**
     * Synchronization object.
     */
    protected final Object threadSync = new Object();


    /**
     * The acceptor thread.
     */
    protected Thread acceptorThread = null;


    /**
     * The socket poller thread.
     */
    protected Thread pollerThread = null;


    /**
     * The sendfile thread.
     */
    protected Thread sendfileThread = null;


    /**
     * Available processors.
     */
    // FIXME: Stack is synced, which makes it a non optimal choice
    protected Stack workers = new Stack();


    /**
     * Running state of the endpoint.
     */
    protected volatile boolean running = false;


    /**
     * Will be set to true whenever the endpoint is paused.
     */
    protected volatile boolean paused = false;


    /**
     * Track the initialization state of the endpoint.
     */
    protected boolean initialized = false;


    /**
     * Current worker threads busy count.
     */
    protected int curThreadsBusy = 0;


    /**
     * Current worker threads count.
     */
    protected int curThreads = 0;


    /**
     * Sequence number used to generate thread names.
     */
    protected int sequence = 0;


    /**
     * Root APR memory pool.
     */
    protected long rootPool = 0;


    /**
     * Server socket "pointer".
     */
    protected long serverSock = 0;


    /**
     * APR memory pool for the server socket.
     */
    protected long serverSockPool = 0;

    
    /**
     * SSL context.
     */
    protected long sslContext = 0;
    

    // ------------------------------------------------------------- Properties


    /**
     * Maximum amount of worker threads.
     */
    protected int maxThreads = 60;
    public void setMaxThreads(int maxThreads) { this.maxThreads = maxThreads; }
    public int getMaxThreads() { return maxThreads; }


    /**
     * Priority of the acceptor and poller threads.
     */
    protected int threadPriority = Thread.NORM_PRIORITY;
    public void setThreadPriority(int threadPriority) { this.threadPriority = threadPriority; }
    public int getThreadPriority() { return threadPriority; }


    /**
     * Size of the socket poller.
     */
    protected int pollerSize = 768;
    public void setPollerSize(int pollerSize) { this.pollerSize = pollerSize; }
    public int getPollerSize() { return pollerSize; }


    /**
     * Size of the sendfile (= concurrent files which can be served).
     */
    protected int sendfileSize = 256;
    public void setSendfileSize(int sendfileSize) { 
        this.sendfileSize = sendfileSize;
        if( sendfile != null ) sendfile.setSendfileSize(sendfileSize);
    }
    public int getSendfileSize() { 
        return sendfileSize; 
    }

    /**
     * Handling of accepted sockets.
     */
    protected Handler handler = null;
    public void setHandler(Handler handler ) { this.handler = handler; }
    public Handler getHandler() { return handler; }

    protected TcpConnectionHandler chandler = null;
    public void setHandler(TcpConnectionHandler chandler ) { this.chandler = chandler; }
    public TcpConnectionHandler getTcpConnectionHandler() { return chandler; }


    /**
     * Allows the server developer to specify the backlog that
     * should be used for server sockets. By default, this value
     * is 100.
     */
    protected int backlog = 100;
    public void setBacklog(int backlog) { if (backlog > 0) this.backlog = backlog; }
    public int getBacklog() { return backlog; }


    /**
     * Timeout on first request read before going to the poller, in ms.
     */
    protected int firstReadTimeout = 100;
    public int getFirstReadTimeout() { return firstReadTimeout; }
    public void setFirstReadTimeout(int firstReadTimeout) { this.firstReadTimeout = firstReadTimeout; }


    /**
     * Poll interval, in microseconds. The smaller the value, the more CPU the poller
     * will use, but the more responsive to activity it will be.
     */
    protected int pollTime = 5000;
    public int getPollTime() { return pollTime; }
    public void setPollTime(int pollTime) { 
        this.pollTime = pollTime;
    }

    /**
     * Use endfile for sending static files.
     */
    protected boolean useSendfile = true;
    public void setUseSendfile(boolean useSendfile) { this.useSendfile = useSendfile; }
    public boolean getUseSendfile() { return useSendfile; }


    /**
     * Number of keepalive sockets.
     */
    protected int keepAliveCount = 0;
    public int getKeepAliveCount() { return keepAliveCount; }


    /**
     * Number of sendfile sockets.
     */
    public int getSendfileCount() { return sendfile.getSendfileCount(); }


    /**
     * The socket poller.
     */
    protected Poller poller = null;
    public Poller getPoller() { return poller; }


    /**
     * The static file sender.
     */
    protected Sendfile sendfile = null;
    public Sendfile getSendfile() { return sendfile; }


    /**
     * Dummy maxSpareThreads property.
     */
    public int getMaxSpareThreads() { return 0; }


    /**
     * Dummy minSpareThreads property.
     */
    public int getMinSpareThreads() { return 0; }

    
    /**
     * SSL engine.
     */
    protected String SSLEngine = "off";
    public String getSSLEngine() { return SSLEngine; }
    public void setSSLEngine(String SSLEngine) { this.SSLEngine = SSLEngine; }

    
    /**
     * SSL protocols.
     */
    protected String SSLProtocol = "all";
    public String getSSLProtocol() { return SSLProtocol; }
    public void setSSLProtocol(String SSLProtocol) { this.SSLProtocol = SSLProtocol; }

    
    /**
     * SSL password (if a cert is encrypted, and no password has been provided, a callback
     * will ask for a password).
     */
    protected String SSLPassword = null;
    public String getSSLPassword() { return SSLPassword; }
    public void setSSLPassword(String SSLPassword) { this.SSLPassword = SSLPassword; }


    /**
     * SSL cipher suite.
     */
    protected String SSLCipherSuite = "ALL";
    public String getSSLCipherSuite() { return SSLCipherSuite; }
    public void setSSLCipherSuite(String SSLCipherSuite) { this.SSLCipherSuite = SSLCipherSuite; }
    
    
    /**
     * SSL certificate file.
     */
    protected String SSLCertificateFile = null;
    public String getSSLCertificateFile() { return SSLCertificateFile; }
    public void setSSLCertificateFile(String SSLCertificateFile) { this.SSLCertificateFile = SSLCertificateFile; }
    

    /**
     * SSL certificate key file.
     */
    protected String SSLCertificateKeyFile = null;
    public String getSSLCertificateKeyFile() { return SSLCertificateKeyFile; }
    public void setSSLCertificateKeyFile(String SSLCertificateKeyFile) { this.SSLCertificateKeyFile = SSLCertificateKeyFile; }

    
    /**
     * SSL certificate chain file.
     */
    protected String SSLCertificateChainFile = null;
    public String getSSLCertificateChainFile() { return SSLCertificateChainFile; }
    public void setSSLCertificateChainFile(String SSLCertificateChainFile) { this.SSLCertificateChainFile = SSLCertificateChainFile; }
    

    /**
     * SSL CA certificate path.
     */
    protected String SSLCACertificatePath = null;
    public String getSSLCACertificatePath() { return SSLCACertificatePath; }
    public void setSSLCACertificatePath(String SSLCACertificatePath) { this.SSLCACertificatePath = SSLCACertificatePath; }
    
    
    /**
     * SSL CA certificate file.
     */
    protected String SSLCACertificateFile = null;
    public String getSSLCACertificateFile() { return SSLCACertificateFile; }
    public void setSSLCACertificateFile(String SSLCACertificateFile) { this.SSLCACertificateFile = SSLCACertificateFile; }
    
    
    /**
     * SSL CA revocation path.
     */
    protected String SSLCARevocationPath = null;
    public String getSSLCARevocationPath() { return SSLCARevocationPath; }
    public void setSSLCARevocationPath(String SSLCARevocationPath) { this.SSLCARevocationPath = SSLCARevocationPath; }
    

    /**
     * SSL CA revocation file.
     */
    protected String SSLCARevocationFile = null;
    public String getSSLCARevocationFile() { return SSLCARevocationFile; }
    public void setSSLCARevocationFile(String SSLCARevocationFile) { this.SSLCARevocationFile = SSLCARevocationFile; }
    
    
    /**
     * SSL verify client.
     */
    protected String SSLVerifyClient = "none";
    public String getSSLVerifyClient() { return SSLVerifyClient; }
    public void setSSLVerifyClient(String SSLVerifyClient) { this.SSLVerifyClient = SSLVerifyClient; }
     
    
    /**
     * SSL verify depth.
     */
    protected int SSLVerifyDepth = 10;
    public int getSSLVerifyDepth() { return SSLVerifyDepth; }
    public void setSSLVerifyDepth(int SSLVerifyDepth) { this.SSLVerifyDepth = SSLVerifyDepth; }
    
    
    // --------------------------------------------------------- Public Methods


    /**
     * Return the amount of threads that are managed by the pool.
     *
     * @return the amount of threads that are managed by the pool
     */
    public int getCurrentThreadCount() {
        return curThreads;
    }


    /**
     * Return the amount of threads currently busy.
     *
     * @return the amount of threads currently busy
     */
    public int getCurrentThreadsBusy() {
        return curThreadsBusy;
    }


    /**
     * Return the state of the endpoint.
     *
     * @return true if the endpoint is running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }


    /**
     * Return the state of the endpoint.
     *
     * @return true if the endpoint is paused, false otherwise
     */
    public boolean isPaused() {
        return paused;
    }


    // ----------------------------------------------- Public Lifecycle Methods


    /**
     * Initialize the endpoint.
     */
    public void init()
        throws Exception {

        if (initialized)
            return;

        // Create the root APR memory pool
        rootPool = Pool.create(0);
        // Create the pool for the server socket
        serverSockPool = Pool.create(rootPool);
        // Create the APR address that will be bound
        String addressStr = null;
        if (inet == null) {
            addressStr = null;
        } else {
            addressStr = inet.getHostAddress();
        }
        long inetAddress = Address.info(addressStr, Socket.APR_INET,
                port, 0, rootPool);
        // Create the APR server socket
        serverSock = Socket.create(Socket.APR_INET, Socket.SOCK_STREAM,
                Socket.APR_PROTO_TCP, rootPool);
        if (OS.IS_UNIX) {
            Socket.optSet(serverSock, Socket.APR_SO_REUSEADDR, 1);    
        }
        // Deal with the firewalls that tend to drop the inactive sockets
        Socket.optSet(serverSock, Socket.APR_SO_KEEPALIVE, 1);
        // Bind the server socket
        int ret = Socket.bind(serverSock, inetAddress);
        if (ret != 0) {
            throw new Exception(sm.getString("endpoint.init.bind", "" + ret));
        }
        // Start listening on the server socket
        ret = Socket.listen(serverSock, backlog);
        if (ret != 0) {
            throw new Exception(sm.getString("endpoint.init.listen", "" + ret));
        }
        if (OS.IS_WIN32 || OS.IS_WIN64) {
            // On Windows set the reuseaddr flag after the bind/listen
            Socket.optSet(serverSock, Socket.APR_SO_REUSEADDR, 1);    
        }

        // Sendfile usage on systems which don't support it cause major problems
        if (useSendfile && !Library.APR_HAS_SENDFILE) {
            log.warn(sm.getString("endpoint.sendfile.nosupport"));
            useSendfile = false;
        }
        
        // Delay accepting of new connections until data is available
        // Only Linux kernels 2.4 + have that implemented
        // on other platforms this call is noop and will return APR_ENOTIMPL.
        Socket.optSet(serverSock, Socket.APR_TCP_DEFER_ACCEPT, 1);
        
        // Initialize SSL if needed
        if (!"off".equalsIgnoreCase(SSLEngine)) {
            // Initialize SSL
            // FIXME: one per VM call ?
            if ("on".equalsIgnoreCase(SSLEngine)) {
                SSL.initialize(null);
            } else {
                SSL.initialize(SSLEngine);
            }
            // SSL protocol
            int value = SSL.SSL_PROTOCOL_ALL;
            if ("SSLv2".equalsIgnoreCase(SSLProtocol)) {
                value = SSL.SSL_PROTOCOL_SSLV2;
            } else if ("SSLv3".equalsIgnoreCase(SSLProtocol)) {
                value = SSL.SSL_PROTOCOL_SSLV3;
            } else if ("TLSv1".equalsIgnoreCase(SSLProtocol)) {
                value = SSL.SSL_PROTOCOL_TLSV1;
            } else if ("SSLv2+SSLv3".equalsIgnoreCase(SSLProtocol)) {
                value = SSL.SSL_PROTOCOL_SSLV2 | SSL.SSL_PROTOCOL_SSLV3;
            }
            // Create SSL Context
            sslContext = SSLContext.make(rootPool, value, SSL.SSL_MODE_SERVER);
            // List the ciphers that the client is permitted to negotiate
            SSLContext.setCipherSuite(sslContext, SSLCipherSuite);
            // Load Server key and certificate
            SSLContext.setCertificate(sslContext, SSLCertificateFile, SSLCertificateKeyFile, SSLPassword, SSL.SSL_AIDX_RSA);
            // Support Client Certificates
            if (SSLCACertificateFile != null) {
                SSLContext.setCACertificate(sslContext, SSLCACertificateFile, null);
            }
            // Client certificate verification
            value = SSL.SSL_CVERIFY_NONE;
            if ("optional".equalsIgnoreCase(SSLVerifyClient)) {
                value = SSL.SSL_CVERIFY_OPTIONAL;
            } else if ("require".equalsIgnoreCase(SSLVerifyClient)) {
                value = SSL.SSL_CVERIFY_REQUIRE;
            } else if ("optionalNoCA".equalsIgnoreCase(SSLVerifyClient)) {
                value = SSL.SSL_CVERIFY_OPTIONAL_NO_CA;
            }
            SSLContext.setVerify(sslContext, value, SSLVerifyDepth);
            // For now, sendfile is not supported with SSL
            useSendfile = false;
        }
        
        initialized = true;

    }


    /**
     * Start the APR endpoint, creating acceptor, poller and sendfile threads.
     */
    public void start()
        throws Exception {
        // Initialize socket if not done before
        if (!initialized) {
            init();
        }
        if (!running) {
            running = true;
            paused = false;

            // Start acceptor thread
            acceptorThread = new Thread(new Acceptor(), getName() + "-Acceptor");
            acceptorThread.setPriority(getThreadPriority());
            acceptorThread.setDaemon(true);
            acceptorThread.start();

            // Start poller thread
            poller = new Poller();
            poller.init();
            pollerThread = new Thread(poller, getName() + "-Poller");
            pollerThread.setPriority(getThreadPriority());
            pollerThread.setDaemon(true);
            pollerThread.start();

            // Start sendfile thread
            if (useSendfile) {
                sendfile = new Sendfile(this, serverSockPool);
                sendfile.setSendfileSize(getSendfileSize());
                sendfile.init();
                sendfileThread = new Thread(sendfile, getName() + "-Sendfile");
                sendfileThread.setPriority(getThreadPriority());
                sendfileThread.setDaemon(true);
                sendfileThread.start();
            }
        }
    }


    /**
     * Pause the endpoint, which will make it stop accepting new sockets.
     */
    public void pause() {
        if (running && !paused) {
            paused = true;
            unlockAccept();
        }
    }


    /**
     * Resume the endpoint, which will make it start accepting new sockets
     * again.
     */
    public void resume() {
        if (running) {
            paused = false;
        }
    }


    /**
     * Stop the endpoint. This will cause all processing threads to stop.
     */
    public void stop() {
        if (running) {
            running = false;
            unlockAccept();
            poller.destroy();
            if (useSendfile) {
                sendfile.destroy();
            }
            acceptorThread = null;
            pollerThread = null;
            sendfileThread = null;
        }
    }


    /**
     * Deallocate APR memory pools, and close server socket.
     */
    public void destroy() throws Exception {
        if (running) {
            stop();
        }
        Pool.destroy(serverSockPool);
        serverSockPool = 0;
        // Close server socket
        Socket.close(serverSock);
        serverSock = 0;
        sslContext = 0;
        // Close all APR memory pools and resources
        Pool.destroy(rootPool);
        rootPool = 0;
        initialized = false ;
    }


    // ------------------------------------------------------ Protected Methods


    /**
     * Get a sequence number used for thread naming.
     */
    protected int getSequence() {
        return sequence++;
    }


    /**
     * Process the specified connection.
     */
    protected boolean setSocketOptions(long socket) {
        // Process the connection
        int step = 1;
        try {

            // 1: Set socket options: timeout, linger, etc
            if (linger >= 0)
                Socket.optSet(socket, Socket.APR_SO_LINGER, linger);
            if (tcpNoDelay)
                Socket.optSet(socket, Socket.APR_TCP_NODELAY, (tcpNoDelay ? 1 : 0));
            if (socketTimeout > 0)
                Socket.timeoutSet(socket, socketTimeout * 1000);

            // 2: SSL handshake
            step = 2;
            if (sslContext != 0) {
                SSLSocket.attach(sslContext, socket);
                if (SSLSocket.handshake(socket) != 0) {
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("endpoint.err.handshake") + ": " + SSL.getLastError());
                    }
                    return false;
                }                                 
            }

        } catch (Throwable t) {
            if (step == 2) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("endpoint.err.handshake"), t);
                }
            } else {
                log.error(sm.getString("endpoint.err.unexpected"), t);
            }
            // Tell to close the socket
            return false;
        }
        return true;
    }


    /**
     * Create (or allocate) and return an available processor for use in
     * processing a specific HTTP request, if possible.  If the maximum
     * allowed processors have already been created and are in use, return
     * <code>null</code> instead.
     */
    protected Worker createWorkerThread() {

        synchronized (workers) {
            if (workers.size() > 0) {
                curThreadsBusy++;
                return ((Worker) workers.pop());
            }
            if ((maxThreads > 0) && (curThreads < maxThreads)) {
                curThreadsBusy++;
                return (newWorkerThread());
            } else {
                if (maxThreads < 0) {
                    curThreadsBusy++;
                    return (newWorkerThread());
                } else {
                    return (null);
                }
            }
        }

    }


    /**
     * Create and return a new processor suitable for processing HTTP
     * requests and returning the corresponding responses.
     */
    protected Worker newWorkerThread() {

        Worker workerThread = new Worker();
        workerThread.start();
        return (workerThread);

    }


    /**
     * Return a new worker thread, and block while to worker is available.
     */
    public Worker getWorkerThread() {
        // Allocate a new worker thread
        Worker workerThread = createWorkerThread();
        while (workerThread == null) {
            try {
                synchronized (workers) {
                    workers.wait();
                }
            } catch (InterruptedException e) {
                // Ignore
            }
            workerThread = createWorkerThread();
        }
        return workerThread;
    }


    /**
     * Recycle the specified Processor so that it can be used again.
     *
     * @param workerThread The processor to be recycled
     */
    protected void recycleWorkerThread(Worker workerThread) {
        synchronized (workers) {
            workers.push(workerThread);
            curThreadsBusy--;
            workers.notify();
        }
    }


    // --------------------------------------------------- Acceptor Inner Class


    /**
     * Server socket acceptor thread.
     */
    protected class Acceptor implements Runnable {


        /**
         * The background thread that listens for incoming TCP/IP connections and
         * hands them off to an appropriate processor.
         */
        public void run() {

            // Loop until we receive a shutdown command
            while (running) {

                // Loop if endpoint is paused
                while (paused) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                // Allocate a new worker thread
                Worker workerThread = getWorkerThread();

                // Accept the next incoming connection from the server socket
                try {
                    long socket = Socket.accept(serverSock);
                    // Hand this socket off to an appropriate processor
                    if (setSocketOptions(socket)) {
                        workerThread.assign(socket);
                    } else {
                        // Close socket and pool right away
                        Socket.destroy(socket);
                    }
                } catch (Exception e) {
                    log.error(sm.getString("endpoint.accept.fail"), e);
                }

                // The processor will recycle itself when it finishes

            }

            // Notify the threadStop() method that we have shut ourselves down
            synchronized (threadSync) {
                threadSync.notifyAll();
            }

        }
    }


    // ----------------------------------------------------- Poller Inner Class


    /**
     * Poller class.
     */
    public class Poller implements Runnable {

        protected long serverPollset = 0;
        protected long pool = 0;
        protected long[] desc;

        protected long[] addS;
        protected int addCount = 0;

        /**
         * Create the poller. With some versions of APR, the maximum poller size will
         * be 62 (reocmpiling APR is necessary to remove this limitation).
         */
        protected void init() {
            pool = Pool.create(serverSockPool);
            try {
                serverPollset = Poll.create(pollerSize, pool, 0, socketTimeout * 1000);
            } catch (Error e) {
                if (Status.APR_STATUS_IS_EINVAL(e.getError())) {
                    try {
                        // Use WIN32 maximum poll size
                        pollerSize = 62;
                        serverPollset = Poll.create(pollerSize, pool, 0, socketTimeout * 1000);
                        log.warn(sm.getString("endpoint.poll.limitedpollsize"));
                    } catch (Error err) {
                        log.error(sm.getString("endpoint.poll.initfail"), e);
                    }
                } else {
                    log.error(sm.getString("endpoint.poll.initfail"), e);
                }
            }
            desc = new long[pollerSize * 2];
            keepAliveCount = 0;
            addS = new long[pollerSize];
            addCount = 0;
        }

        /**
         * Destroy the poller.
         */
        protected void destroy() {
            // Close all sockets in the add queue
            for (int i = 0; i < addCount; i--) {
                Socket.destroy(addS[i]);
            }
            // Close all sockets still in the poller
            int rv = Poll.pollset(serverPollset, desc);
            if (rv > 0) {
                for (int n = 0; n < rv; n++) {
                    Socket.destroy(desc[n*2+1]);
                }
            }
            Pool.destroy(pool);
            keepAliveCount = 0;
            addCount = 0;
        }

        /**
         * Add specified socket and associated pool to the poller. The socket will
         * be added to a temporary array, and polled first after a maximum amount
         * of time equal to pollTime (in most cases, latency will be much lower,
         * however).
         *
         * @param socket to add to the poller
         */
        public void add(long socket) {
            synchronized (addS) {
                // Add socket to the list. Newly added sockets will wait
                // at most for pollTime before being polled
                if (addCount >= addS.length) {
                    // Can't do anything: close the socket right away
                    Socket.destroy(socket);
                    return;
                }
                addS[addCount] = socket;
                addCount++;
                addS.notify();
            }
        }

        /**
         * The background thread that listens for incoming TCP/IP connections and
         * hands them off to an appropriate processor.
         */
        public void run() {

            long maintainTime = 0;
            // Loop until we receive a shutdown command
            while (running) {
                // Loop if endpoint is paused
                while (paused) {
                    try {
                        // TODO: We can easly do the maintenance here
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                while (keepAliveCount < 1 && addCount < 1) {
                    // Reset maintain time.
                    maintainTime = 0;
                    try {
                        synchronized (addS) {
                            addS.wait();
                        }
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                try {
                    // Add sockets which are waiting to the poller
                    if (addCount > 0) {
                        synchronized (addS) {
                            for (int i = (addCount - 1); i >= 0; i--) {
                                int rv = Poll.add
                                    (serverPollset, addS[i], Poll.APR_POLLIN);
                                if (rv == Status.APR_SUCCESS) {
                                    keepAliveCount++;
                                } else {
                                    // Can't do anything: close the socket right away
                                    Socket.destroy(addS[i]);
                                }
                            }
                            addCount = 0;
                        }
                    }
                    maintainTime += pollTime;
                    // Pool for the specified interval
                    int rv = Poll.poll(serverPollset, pollTime, desc, true);
                    if (rv > 0) {
                        keepAliveCount -= rv;
                        for (int n = 0; n < rv; n++) {
                            // Check for failed sockets
                            if (((desc[n*2] & Poll.APR_POLLHUP) == Poll.APR_POLLHUP)
                                    || ((desc[n*2] & Poll.APR_POLLERR) == Poll.APR_POLLERR)) {
                                // Close socket and clear pool
                                Socket.destroy(desc[n*2+1]);
                                continue;
                            }
                            // Hand this socket off to a worker
                            getWorkerThread().assign(desc[n*2+1]);
                        }
                    } else if (rv < 0) {
                        /* Any non timeup error is critical */
                        if (-rv != Status.TIMEUP) {
                            log.error(sm.getString("endpoint.poll.fail", Error.strerror(-rv)));
                            // Handle poll critical failure
                            synchronized (this) {
                                destroy();
                                init();
                            }
                        }
                    }
                    if (socketTimeout > 0 && maintainTime > 1000000L) {
                        rv = Poll.maintain(serverPollset, desc, true);
                        maintainTime = 0;
                        if (rv > 0) {
                            keepAliveCount -= rv;
                            for (int n = 0; n < rv; n++) {
                                // Close socket and clear pool
                                Socket.destroy(desc[n]);
                            }
                        }
                    }
                } catch (Throwable t) {
                    log.error(sm.getString("endpoint.poll.error"), t);
                }

            }

            // Notify the threadStop() method that we have shut ourselves down
            synchronized (threadSync) {
                threadSync.notifyAll();
            }

        }

    }


    // ----------------------------------------------------- Worker Inner Class


    /**
     * Server processor class.
     */
    public class Worker implements Runnable {


        protected Thread thread = null;
        protected boolean available = false;
        protected long socket = 0;


        /**
         * Process an incoming TCP/IP connection on the specified socket.  Any
         * exception that occurs during processing must be logged and swallowed.
         * <b>NOTE</b>:  This method is called from our Connector's thread.  We
         * must assign it to our own thread so that multiple simultaneous
         * requests can be handled.
         *
         * @param socket TCP socket to process
         */
        public synchronized void assign(long socket) {

            // Wait for the Processor to get the previous Socket
            while (available) {
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            }

            // Store the newly available Socket and notify our thread
            this.socket = socket;
            available = true;
            notifyAll();

        }


        /**
         * Await a newly assigned Socket from our Connector, or <code>null</code>
         * if we are supposed to shut down.
         */
        protected synchronized long await() {

            // Wait for the Connector to provide a new Socket
            while (!available) {
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            }

            // Notify the Connector that we have received this Socket
            long socket = this.socket;
            available = false;
            notifyAll();

            return (socket);

        }


        /**
         * The background thread that listens for incoming TCP/IP connections and
         * hands them off to an appropriate processor.
         */
        public void run() {

            // Process requests until we receive a shutdown signal
            TcpConnection tcpCon=new TcpConnection();
            while (running) {

                // Wait for the next socket to be assigned
                long socket = await();
                if (socket == 0)
                    continue;

                // Process the request from this socket
                if (handler != null && !handler.process(socket)) {
                    // Close socket and pool
                    Socket.destroy(socket);
                    socket = 0;
                } else {
                    if (chandler != null ) {
                        tcpCon.setNativeSocket(socket);
                        chandler.processConnection(tcpCon, null);
                        // TODO: Close socket and pool - what would be the 
                        // return false case ? 
                        //Socket.destroy(socket);
                        //socket = 0;
                    }
                }
                // Finish up this request
                recycleWorkerThread(this);

            }

            // Tell threadStop() we have shut ourselves down successfully
            threadSyncNotify();

        }


        /**
         * Start the background processing thread.
         */
        public void start() {
            thread = new ThreadWithAttributes(AprEndpoint.this, this);
            thread.setName(getName() + "-" + (++curThreads));
            thread.setDaemon(true);
            thread.start();
        }

    }

    // TODO: theradEnd event, etc
    public void threadSyncNotify() {
        synchronized (this) {
            threadSync.notifyAll();
        }        
    }

    // ----------------------------------------------- SendfileData Inner Class


    // --------------------------------------------------- Sendfile Inner Class


    /**
     * Bare bones interface used for socket processing. Per thread data is to be
     * stored in the ThreadWithAttributes extra folders, or alternately in
     * thread local fields.
     */
    public interface Handler {
        public boolean process(long socket);
    }


}
