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
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.AccessControlException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tomcat.util.threads.ThreadWithAttributes;


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
public class SimpleEndpoint extends PoolTcpEndpoint { 

    static Log log=LogFactory.getLog(SimpleEndpoint.class );

    private final Object threadSync = new Object();

    // active acceptors
    private int acceptors=0;
    
    
    public SimpleEndpoint() {
        maxSpareThreads = 4;
        minSpareThreads = 1;
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
        Thread t=new ThreadWithAttributes( this, new AcceptorRunnable());
        t.setName("Tomcat-" + acceptors);
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

    // -------------------- Private methods

    Socket acceptSocket() {
        if( !running || serverSocket==null ) return null;

        Socket accepted = null;

    	try {
    	    accepted = serverSocket.accept();
            if (null == accepted) {
                log.warn(sm.getString("endpoint.warn.nullSocket"));
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

    public void processSocket(Socket s, TcpConnection con, Object[] threadData) {
        // Process the connection
        int step = 1;
        try {
            
            // 1: Set socket options: timeout, linger, etc
            setSocketOptions(s);
            
            // 2: SSL handshake
            step = 2;
            
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
    
    public void run() {
        // nothing here, all action is in AcceptorRunnable
    }

    class AcceptorRunnable implements Runnable {
        private TcpConnection con = new TcpConnection();

    
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
            Object[] threadData = getConnectionHandler().init();
            while( running ) {
                // Loop if endpoint is paused
                if( checkSpares() ) {
                    return;
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
                processSocket(socket, con, threadData);
                
                // Finish up this request
                curThreads--;
                
                if( checkSpares() ) {
                    return;
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
