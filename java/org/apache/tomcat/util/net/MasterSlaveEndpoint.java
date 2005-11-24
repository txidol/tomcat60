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
import java.io.InterruptedIOException;
import java.net.BindException;
import java.net.Socket;
import java.net.SocketException;
import java.security.AccessControlException;
import java.util.Stack;
import java.util.Vector;

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
 * This does not use the ThreadPool class ( LeaderFollower does ). Instead
 * a mini thread pool is implemented inside.
 *
 * @author James Duncan Davidson [duncan@eng.sun.com]
 * @author Jason Hunter [jch@eng.sun.com]
 * @author James Todd [gonzo@eng.sun.com]
 * @author Costin@eng.sun.com
 * @author Gal Shachor [shachor@il.ibm.com]
 * @author Yoav Shapira <yoavs@apache.org>
 */
public class MasterSlaveEndpoint extends PoolTcpEndpoint { // implements Endpoint {

    private final Object threadSync = new Object();

    private ServerSocketFactory factory;

    
    // ------ Leader follower fields
    
    // ------ Master slave fields

    /* The background thread. */
    private Thread thread = null;
    /* Available processors. */
    private Stack workerThreads = new Stack();
    /* All processors which have been created. */
    private Vector created = new Vector();

    
    public MasterSlaveEndpoint() {
    }

    // -------------------- Configuration --------------------

    public void setServerSocketFactory(  ServerSocketFactory factory ) {
	    this.factory=factory;
    }

   ServerSocketFactory getServerSocketFactory() {
 	    return factory;
   }

    public String getStrategy() {
        return "ms";
    }
    
    public void setStrategy(String strategy) {
    }

    public int getCurrentThreadsBusy() {
        return curThreads - workerThreads.size();
    }
    
    // -------------------- Public methods --------------------

    public void initEndpoint() throws IOException, InstantiationException {
        try {
            if(factory==null)
                factory=ServerSocketFactory.getDefault();
            if(serverSocket==null) {
                try {
                    if (inet == null) {
                        serverSocket = factory.createSocket(port, backlog);
                    } else {
                        serverSocket = factory.createSocket(port, backlog, inet);
                    }
                } catch ( BindException be ) {
                    throw new BindException(be.getMessage() + ":" + port);
                }
            }
            if( serverTimeout >= 0 )
                serverSocket.setSoTimeout( serverTimeout );
        } catch( IOException ex ) {
            throw ex;
        } catch( InstantiationException ex1 ) {
            throw ex1;
        }
        initialized = true;
    }
    
    public void startEndpoint() throws IOException, InstantiationException {
        if (!initialized) {
            initEndpoint();
        }
        running = true;
        paused = false;
        maxThreads = getMaxThreads();
        threadStart();
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

    // -------------------- Private methods

    Socket acceptSocket() {
        if( !running || serverSocket==null ) return null;

        Socket accepted = null;

    	try {
            if(factory==null) {
                accepted = serverSocket.accept();
            } else {
                accepted = factory.acceptSocket(serverSocket);
            }
            if (null == accepted) {
                log.warn(sm.getString("endpoint.warn.nullSocket"));
            } else {
                if (!running) {
                    accepted.close();  // rude, but unlikely!
                    accepted = null;
                } else if (factory != null) {
                    factory.initSocket( accepted );
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
            String msg = sm.getString("endpoint.warn.security",
                                      serverSocket, ace);
            log.warn(msg);
        }
        catch (IOException e) {

            String msg = null;

            if (running) {
                msg = sm.getString("endpoint.err.nonfatal",
                        serverSocket, e);
                log.error(msg, e);
            }

            if (accepted != null) {
                try {
                    accepted.close();
                } catch(Throwable ex) {
                    msg = sm.getString("endpoint.err.nonfatal",
                                       accepted, ex);
                    log.warn(msg, ex);
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
                        msg = sm.getString("endpoint.warn.reinit");
                        log.warn(msg);
                        initEndpoint();
                    } catch (Throwable t) {
                        msg = sm.getString("endpoint.err.nonfatal",
                                           serverSocket, t);
                        log.error(msg, t);
                    }
                    // 3) If failed, attempt to restart endpoint
                    if (!initialized) {
                        msg = sm.getString("endpoint.warn.restart");
                        log.warn(msg);
                        try {
                            stopEndpoint();
                            initEndpoint();
                            startEndpoint();
                        } catch (Throwable t) {
                            msg = sm.getString("endpoint.err.fatal",
                                               serverSocket, t);
                            log.error(msg, t);
                        }
                        // Current thread is now invalid: kill it
                        throw new ThreadDeath();
                    }
                }
            }

        }

        return accepted;
    }

    
    protected void processSocket(Socket s, TcpConnection con, Object[] threadData) {
        // Process the connection
        int step = 1;
        try {
            
            // 1: Set socket options: timeout, linger, etc
            setSocketOptions(s);
            
            // 2: SSL handshake
            step = 2;
            if (getServerSocketFactory() != null) {
                getServerSocketFactory().handshake(s);
            }
            
            // 3: Process the connection
            step = 3;
            con.setEndpoint(this);
            con.setSocket(s);
            getConnectionHandler().processConnection(con, threadData);
            
        } catch (SocketException se) {
            log.error(sm.getString("endpoint.err.socket", s.getInetAddress()),
                    se);
            // Try to close the socket
            try {
                s.close();
            } catch (IOException e) {
            }
        } catch (Throwable t) {
            if (step == 2) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("endpoint.err.handshake"), t);
                }
            } else {
                log.error(sm.getString("endpoint.err.unexpected"), t);
            }
            // Try to close the socket
            try {
                s.close();
            } catch (IOException e) {
            }
        } finally {
            if (con != null) {
                con.recycle();
            }
        }
    }
    

    // -------------------------------------------------- Master Slave Methods


    /**
     * Create (or allocate) and return an available processor for use in
     * processing a specific HTTP request, if possible.  If the maximum
     * allowed processors have already been created and are in use, return
     * <code>null</code> instead.
     */
    private MasterSlaveWorkerThread createWorkerThread() {

        synchronized (workerThreads) {
            if (workerThreads.size() > 0) {
                return ((MasterSlaveWorkerThread) workerThreads.pop());
            }
            if ((maxThreads > 0) && (curThreads < maxThreads)) {
                return (newWorkerThread());
            } else {
                if (maxThreads < 0) {
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
    private MasterSlaveWorkerThread newWorkerThread() {

        MasterSlaveWorkerThread workerThread = 
            new MasterSlaveWorkerThread(this, getName() + "-" + (++curThreads));
        workerThread.start();
        created.addElement(workerThread);
        return (workerThread);

    }


    /**
     * Recycle the specified Processor so that it can be used again.
     *
     * @param processor The processor to be recycled
     */
    public void workerDone(Runnable workerThread) {
        workerThreads.push(workerThread);
    }

    
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
            MasterSlaveWorkerThread workerThread = createWorkerThread();
            if (workerThread == null) {
                try {
                    // Wait a little for load to go down: as a result, 
                    // no accept will be made until the concurrency is
                    // lower than the specified maxThreads, and current
                    // connections will wait for a little bit instead of
                    // failing right away.
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    // Ignore
                }
                continue;
            }
            
            // Accept the next incoming connection from the server socket
            Socket socket = acceptSocket();

            // Hand this socket off to an appropriate processor
            workerThread.assign(socket);

            // The processor will recycle itself when it finishes

        }

        // Notify the threadStop() method that we have shut ourselves down
        synchronized (threadSync) {
            threadSync.notifyAll();
        }

    }


    /**
     * Start the background processing thread.
     */
    private void threadStart() {
        thread = new Thread(this, getName());
        thread.setPriority(getThreadPriority());
        thread.setDaemon(true);
        thread.start();
    }


    /**
     * Stop the background processing thread.
     */
    private void threadStop() {
        thread = null;
    }

    public void setSSLSupport(boolean secure, String factoryName) throws Exception {
        if (secure) {
            try {
                // The SSL setup code has been moved into
                // SSLImplementation since SocketFactory doesn't
                // provide a wide enough interface
                SSLImplementation sslImplementation =
                    SSLImplementation.getInstance(factoryName);
                ServerSocketFactory socketFactory = 
                    sslImplementation.getServerSocketFactory();
                this.setServerSocketFactory(socketFactory);
            } catch (ClassNotFoundException e){
                throw e;
            }
        } else if (factoryName != null) {
            try {
                ServerSocketFactory socketFactory = 
                    string2SocketFactory(factoryName);
                this.setServerSocketFactory(socketFactory);
            } catch(Exception sfex) {
                throw sfex;
            }
        }
    }

    private static ServerSocketFactory string2SocketFactory( String val)
        throws Exception
    {
        Class chC=Class.forName( val );
        return (ServerSocketFactory)chC.newInstance();
    }


}
