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

package org.apache.tomcat.util.net.simple;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.AccessControlException;
import java.util.ArrayList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;


/**
 * Very simple endpoint - no thread pool, no recycling, etc.
 * 
 * Relies the JVM thread pool, if any. 
 * 
 * Used for embedded use cases, where you wouldn't expect a huge load, and
 * memory is important ( no caching or keeping around objects ).
 *
 * Note that ServerSocket and the whole SSL machinery is also gone - instead
 * use a subclass that extends this and knows about ssl. We may add it back, but
 * needs to be fixed.
 * 
 * @author Costin Manolache ( costin@apache.org )
 */
public class SimpleEndpoint { 

    static Log log=LogFactory.getLog(SimpleEndpoint.class );

    private final Object threadSync = new Object();

    // active acceptors
    private int acceptors=0;
    
    protected static final int BACKLOG = 100;
    protected static final int TIMEOUT = 1000;

    protected int backlog = BACKLOG;
    protected int serverTimeout = TIMEOUT;

    protected InetAddress inet;
    protected int port = 8080;

    protected ServerSocket serverSocket;

    protected volatile boolean running = false;
    protected volatile boolean paused = false;
    protected boolean initialized = false;
    protected boolean reinitializing = false;

    protected boolean tcpNoDelay=false;
    protected int linger=100;
    protected int socketTimeout=-1;
    
    
    // ------ Leader follower fields
    public interface Handler {
        public boolean process(Socket socket);
    }
    
    Handler handler;
    // ------ Master slave fields

    protected int curThreads = 0;
    protected int maxThreads = 20;
    protected int maxSpareThreads = 20;
    protected int minSpareThreads = 20;
    protected String type = "default";
    // to name the threads and get an idea how many threads were closed
    protected int threadId = 0;

    protected String name = "EP"; // base name for threads
    
    protected int threadPriority;

    protected boolean daemon = true;

    private ArrayList listeners = new ArrayList();

    private boolean polling;
    
    
    public SimpleEndpoint() {
        maxSpareThreads = 4;
        minSpareThreads = 2;
    }

    // --- From PoolTcpEndpoint
    
    public static interface EndpointListener {
        public void threadStart(SimpleEndpoint ep, Thread t);

        public void threadEnd( SimpleEndpoint ep, Thread t);
    }

    
    protected void threadEnd(Thread t) {
        for( int i=0; i<listeners.size(); i++ ) {
            EndpointListener tpl=(EndpointListener)listeners.get(i);
            tpl.threadStart(this, t);
        }        
    }

    protected void threadStart(Thread t) {
        for( int i=0; i<listeners.size(); i++ ) {
            EndpointListener tpl=(EndpointListener)listeners.get(i);
            tpl.threadStart(this, t);
        }        
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
                log.debug("Error unlocking: " + port, e);
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

    // --- PoolTcpEndpoint - getters and setters 
    
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

    public void setConnectionHandler( Handler handler ) {
        this.handler=handler;
    }

    public Handler getConnectionHandler() {
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

    public void setDaemon(boolean b) {
        daemon=b;
    }
    
    public boolean getDaemon() {
        return daemon;
    }


    // -------------------- Public methods --------------------

    public void initEndpoint() throws IOException, InstantiationException {
        try {
            if(serverSocket==null) {
                try {
                    if (inet == null) {
                        serverSocket = new ServerSocket(port, backlog);
                    } else {
                        serverSocket = new ServerSocket(port, backlog, inet);
                    }
                } catch ( BindException be ) {
                    throw new BindException(be.getMessage() + ":" + port);
                }
            }
            if( serverTimeout >= 0 )
                serverSocket.setSoTimeout( serverTimeout );
            
        } catch( IOException ex ) {
            throw ex;
        }
        initialized = true;
    }
    
    public void startEndpoint() throws IOException, InstantiationException {
        if (!initialized) {
            initEndpoint();
        }
        running = true;
        paused = false;
        if( maxSpareThreads == minSpareThreads ) {
            maxSpareThreads = minSpareThreads + 4;
        }

        // Start the first thread
        checkSpares();
    }

    /** Check the spare situation. If not enough - create more.
     * If too many - return true to end this.
     * 
     * This is the main method to handle the number of threads.
     * 
     * @return
     */
    boolean checkSpares() {
        // make sure we have min spare threads
        while( (acceptors - curThreads ) < minSpareThreads ) {
            if( acceptors >= maxThreads ) {
                // limit reached, we won't accept any more requests. 
            } else {
                newAcceptor();
            }
        }
        
        if( acceptors - curThreads > maxSpareThreads ) {
            threadEnd( Thread.currentThread() );
            return true; // this one should go
        }
        
        return false;
    }

    void newAcceptor() {
        acceptors++;
        Thread t=new Thread( new AcceptorRunnable());
        t.setName("Tomcat-" + threadId++);
        if( threadPriority > 0 ) {
            t.setPriority(threadPriority);
        }
        t.setDaemon(daemon);
        threadStart( t );
        t.start();        
    }
    
    
    public void pauseEndpoint() {
        if (running && !paused) {
            paused = true;
            unlockAccept();
        }
    }

    public void resumeEndpoint() {
        if (running) {
            paused = false;
        }
    }

    public void stopEndpoint() {
        if (running) {
            running = false;
            if (serverSocket != null) {
                closeServerSocket();
            }
            initialized=false ;
        }
    }

    protected void closeServerSocket() {
        if (!paused)
            unlockAccept();
        try {
            if( serverSocket!=null)
                serverSocket.close();
        } catch(Exception e) {
            log.error("Exception", e);
        }
        serverSocket = null;
    }

    // -------------------- Private methods

    Socket acceptSocket() {
        if( !running || serverSocket==null ) return null;

        Socket accepted = null;

    	try {
    	    accepted = serverSocket.accept();
            if (null == accepted) {
                log.warn("Accepted null socket");
            } else {
                if (!running) {
                    accepted.close();  // rude, but unlikely!
                    accepted = null;
                }
            }
        }
        catch(InterruptedIOException iioe) {
            // normal part -- should happen regularly so
            // that the endpoint can release if the server
            // is shutdown.
        }
        catch (AccessControlException ace) {
            // When using the Java SecurityManager this exception
            // can be thrown if you are restricting access to the
            // socket with SocketPermission's.
            // Log the unauthorized access and continue
            log.warn("AccessControlException", ace);
        }
        catch (IOException e) {

            if (running) {
                log.error("IOException", e);
            }

            if (accepted != null) {
                try {
                    accepted.close();
                } catch(Throwable ex) {
                    log.warn("IOException in close()", ex);
                }
                accepted = null;
            }

            if( ! running ) return null;
            reinitializing = true;
            // Restart endpoint when getting an IOException during accept
            synchronized (threadSync) {
                if (reinitializing) {
                    reinitializing = false;
                    // 1) Attempt to close server socket
                    closeServerSocket();
                    initialized = false;
                    // 2) Reinit endpoint (recreate server socket)
                    try {
                        log.warn("Reinit endpoint");
                        initEndpoint();
                    } catch (Throwable t) {
                        log.error("Error in reinit", t);
                    }
                    // 3) If failed, attempt to restart endpoint
                    if (!initialized) {
                        log.warn("Restart endpoint");
                        try {
                            stopEndpoint();
                            initEndpoint();
                            startEndpoint();
                        } catch (Throwable t) {
                            log.error("Error in restart", t);
                        }
                        // Current thread is now invalid: kill it
                        throw new ThreadDeath();
                    }
                }
            }

        }

        return accepted;
    }

    public void processSocket(Socket s) {
        // Process the connection
        int step = 1;
        try {
            
            // 1: Set socket options: timeout, linger, etc
            setSocketOptions(s);
            
            // 2: SSL handshake
            step = 2;
            
            // 3: Process the connection
            step = 3;
            handler.process(s);
            
        } catch (SocketException se) {
            log.error("Socket error " + s.getInetAddress(), se);
            // Try to close the socket
            try {
                s.close();
            } catch (IOException e) {
            }
        } catch (Throwable t) {
            if (step == 2) {
                if (log.isDebugEnabled()) {
                    log.debug("Error in handshake", t);
                }
            } else {
                log.error("Unexpected error", t);
            }
            // Try to close the socket
            try {
                s.close();
            } catch (IOException e) {
            }
        } finally {
        }
    }
    
    public void run() {
        // nothing here, all action is in AcceptorRunnable
    }

    class AcceptorRunnable implements Runnable {
        //private TcpConnection con = new TcpConnection();

    
        /**
         * Accept, dispatch on a new thread. May benefit from VM thread pooling, but
         * the goal is to minimize the number of resources used.
         * 
         * TODO: change this to use NIO, use the thread for other control events
         * ( timers, etc ) that would require a separate thread.
         * 
         * TODO: maybe add back ability to do pooling, by refactoring ThreadPool
         * or adding some optional interface. Maybe better abstract the other endpoint 
         * thread models in a new TP interface.
         */
        public void run() {
            Object[] threadData = null; //getConnectionHandler().init();
            while( running ) {
                // Loop if endpoint is paused
                if( checkSpares() ) {
                    break;
                }
                
                while (paused) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }
                
                Socket socket = acceptSocket();
                
                curThreads++;
                
                // Process the request from this socket
                processSocket(socket);
                
                // Finish up this request
                curThreads--;
                
                if( checkSpares() ) {
                    break;
                    // return;
                }
            }
            
            acceptors--; // we're done
            
            // Notify the threadStop() method that we have shut ourselves down
            synchronized (threadSync) {
                threadSync.notifyAll();
            }
        }
    }
}
