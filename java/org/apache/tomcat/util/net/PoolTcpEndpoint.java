/*
 *  Copyright 1999-2004 The Apache Software Foundation
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

package org.apache.tomcat.util.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tomcat.util.res.StringManager;

/* Similar with MPM module in Apache2.0. Handles all the details related with
   "tcp server" functionality - thread management, accept policy, etc.
   It should do nothing more - as soon as it get a socket ( and all socket options
   are set, etc), it just handle the stream to ConnectionHandler.processConnection. (costin)
*/



/**
 * Handle incoming TCP connections.
 *
 * This class implement a simple server model: one listener thread accepts on a socket and
 * creates a new worker thread for each incoming connection.
 *
 * More advanced Endpoints will reuse the threads, use queues, etc.
 *
 * @author James Duncan Davidson [duncan@eng.sun.com]
 * @author Jason Hunter [jch@eng.sun.com]
 * @author James Todd [gonzo@eng.sun.com]
 * @author Costin@eng.sun.com
 * @author Gal Shachor [shachor@il.ibm.com]
 * @author Yoav Shapira <yoavs@apache.org>
 */
public class PoolTcpEndpoint implements Runnable { // implements Endpoint {

    protected static Log log=LogFactory.getLog(PoolTcpEndpoint.class );

    protected StringManager sm = 
        StringManager.getManager("org.apache.tomcat.util.net.res");

    protected static final int BACKLOG = 100;
    protected static final int TIMEOUT = 1000;

    protected int backlog = BACKLOG;
    protected int serverTimeout = TIMEOUT;

    protected InetAddress inet;
    protected int port;

    protected ServerSocket serverSocket;

    protected volatile boolean running = false;
    protected volatile boolean paused = false;
    protected boolean initialized = false;
    protected boolean reinitializing = false;

    protected boolean tcpNoDelay=false;
    protected int linger=100;
    protected int socketTimeout=-1;
    
    // ------ Leader follower fields

    
    TcpConnectionHandler handler;
    // ------ Master slave fields

    protected int curThreads = 0;
    protected int maxThreads = 20;
    protected int maxSpareThreads = 20;
    protected int minSpareThreads = 20;
    protected String type = "default";

    protected String name = "EP"; // base name for threads
    
    protected int threadPriority;

    protected boolean daemon = true;

    private ArrayList listeners = new ArrayList();

    private boolean polling;
    
    public PoolTcpEndpoint() {
    }

    public static PoolTcpEndpoint getEndpoint(String type) {
        String cn = null;
        if( "apr".equals( type )) {
            cn = "org.apache.tomcat.util.net.apr.AprEndpoint";
        }
        if( "lf".equals( type )) {
            cn = "org.apache.tomcat.util.net.javaio.LeaderFollowerEndpoint";
        }
        if( "ms".equals( type )) {
            cn = "org.apache.tomcat.util.net.javaio.MasterSlaveEndpoint";
        }
        if( "nio".equals( type )) {
            cn = "org.apache.tomcat.util.net.nio.NioEndpoint";
        }
        PoolTcpEndpoint res = null; 
        if( cn != null ) {
            try {
                Class c = Class.forName( cn );
                res = (PoolTcpEndpoint)c.newInstance();
            } catch( Throwable t ) {
                throw new RuntimeException("Can't create endpoint " + cn);
            }            
        }
        if( res == null ) {
            res = new SimpleEndpoint();
        }
        res.type = type;
        return res;
    }

    // -------------------- Configuration --------------------
    
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    
    public void setMaxThreads(int maxThreads) {
        this.maxThreads = maxThreads;
    }

    public int getMaxThreads() {
        return maxThreads;
    }

    public void setMaxSpareThreads(int maxThreads) {
        this.maxSpareThreads = maxThreads;
    }

    public int getMaxSpareThreads() {
        return maxSpareThreads;
    }

    public void setMinSpareThreads(int minThreads) {
        this.minSpareThreads = minThreads;
    }

    public int getMinSpareThreads() {
        return minSpareThreads;
    }

    public void setThreadPriority(int threadPriority) {
        this.threadPriority = threadPriority;
    }

    public int getThreadPriority() {
        return threadPriority;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port ) {
        this.port=port;
    }

    public InetAddress getAddress() {
	    return inet;
    }

    public void setAddress(InetAddress inet) {
	    this.inet=inet;
    }

    public void setServerSocket(ServerSocket ss) {
	    serverSocket = ss;
    }
    
    public ServerSocket getServerSocket() {
        return serverSocket;
    }

    public void setConnectionHandler( TcpConnectionHandler handler ) {
    	this.handler=handler;
    }

    public TcpConnectionHandler getConnectionHandler() {
	    return handler;
    }

    public boolean isRunning() {
	return running;
    }
    
    public boolean isPaused() {
	return paused;
    }
    
    /**
     * Allows the server developer to specify the backlog that
     * should be used for server sockets. By default, this value
     * is 100.
     */
    public void setBacklog(int backlog) {
	if( backlog>0)
	    this.backlog = backlog;
    }

    public int getBacklog() {
        return backlog;
    }

    /**
     * Sets the timeout in ms of the server sockets created by this
     * server. This method allows the developer to make servers
     * more or less responsive to having their server sockets
     * shut down.
     *
     * <p>By default this value is 1000ms.
     */
    public void setServerTimeout(int timeout) {
	this.serverTimeout = timeout;
    }

    public boolean getTcpNoDelay() {
        return tcpNoDelay;
    }
    
    public void setTcpNoDelay( boolean b ) {
	tcpNoDelay=b;
    }

    public int getSoLinger() {
        return linger;
    }
    
    public void setSoLinger( int i ) {
	linger=i;
    }

    public int getSoTimeout() {
        return socketTimeout;
    }
    
    public void setSoTimeout( int i ) {
	socketTimeout=i;
    }
    
    public int getServerSoTimeout() {
        return serverTimeout;
    }  
    
    public void setServerSoTimeout( int i ) {
	serverTimeout=i;
    }

    public String getStrategy() {
        return type;
    }
    
    public void setStrategy(String strategy) {
        // shouldn't be used.
    }

    public int getCurrentThreadCount() {
        return curThreads;
    }
    
    public int getCurrentThreadsBusy() {
        return curThreads;
    }

    public boolean getPolling() {
        return polling;
    }
    
    public void setPolling( boolean b ) {
        polling = b;
    }


    // -------------------- Public methods --------------------

    public void initEndpoint() throws IOException, InstantiationException {
    }
    
    public void startEndpoint() throws IOException, InstantiationException {
    }

    public void pauseEndpoint() {
    }

    public void resumeEndpoint() {
    }

    public void stopEndpoint() {
    }

    public void processSocket(Socket s, TcpConnection con, 
            Object[] threadData) {
    }


    /** To notify worker done, recycle
     */
    public void workerDone(Runnable r) {
        
    }

    /** If the endpoint supports polling, add the socket to the poll
     * watch. A thread will be woked up when the socket has available data.
     * 
     * Use this for Keep-Alive, or for reading data from client in poll mode.
     * 
     * 
     * @param s
     * @param context will be made available to the thread.
     */
    public void addPolling(Socket s, Object context ) {
        
    }
    
    // ---------------- Utils ----------------------
    
    protected void closeServerSocket() {
        if (!paused)
            unlockAccept();
        try {
            if( serverSocket!=null)
                serverSocket.close();
        } catch(Exception e) {
            log.error(sm.getString("endpoint.err.close"), e);
        }
        serverSocket = null;
    }

    protected void unlockAccept() {
        Socket s = null;
        try {
            // Need to create a connection to unlock the accept();
            if (inet == null) {
                s = new Socket("127.0.0.1", port);
            } else {
                s = new Socket(inet, port);
                    // setting soLinger to a small value will help shutdown the
                    // connection quicker
                s.setSoLinger(true, 0);
            }
        } catch(Exception e) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("endpoint.debug.unlock", "" + port), e);
            }
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
    }

    protected void setSocketOptions(Socket socket)
        throws SocketException {
        if(linger >= 0 ) 
            socket.setSoLinger( true, linger);
        if( tcpNoDelay )
            socket.setTcpNoDelay(tcpNoDelay);
        if( socketTimeout > 0 )
            socket.setSoTimeout( socketTimeout );
    }

    /**
     * The background thread that listens for incoming TCP/IP connections and
     * hands them off to an appropriate processor.
     */
    public void run() {
    }

    public void setSSLSupport(boolean secure, String socketFactoryName) throws Exception {
    }

    public void setDaemon(boolean b) {
        daemon=b;
    }
    
    public boolean getDaemon() {
        return daemon;
    }

    protected void threadStart(Thread t) {
        for( int i=0; i<listeners.size(); i++ ) {
            EndpointListener tpl=(EndpointListener)listeners.get(i);
            tpl.threadStart(this, t);
        }        
    }

    protected void threadEnd(Thread t) {
        for( int i=0; i<listeners.size(); i++ ) {
            EndpointListener tpl=(EndpointListener)listeners.get(i);
            tpl.threadStart(this, t);
        }        
    }
    
    public void addEndpointListener(EndpointListener listener) {
        listeners.add(listener);
    }
 
    public static interface EndpointListener {
        public void threadStart( PoolTcpEndpoint ep, Thread t);

        public void threadEnd( PoolTcpEndpoint ep, Thread t);

    }

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

    public Object getSsl(String string) {
        return null;
    }
}
