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

import org.apache.tomcat.util.threads.ThreadPool;
import org.apache.tomcat.util.threads.ThreadPoolRunnable;

/* Similar with MPM module in Apache2.0. Handles all the details related with
   "tcp server" functionality - thread management, accept policy, etc.
   It should do nothing more - as soon as it get a socket ( and all socket options
   are set, etc), it just handle the stream to ConnectionHandler.processConnection. (costin)
*/



/**
 * Handle incoming TCP connections.
 * 
 * Each thread in the pool accepts, then process a request. A spare thread
 * will take over the accept.
 *
 * TODO: can we have all threads in the pool blocked on accept ? 
 *
 * @author James Duncan Davidson [duncan@eng.sun.com]
 * @author Jason Hunter [jch@eng.sun.com]
 * @author James Todd [gonzo@eng.sun.com]
 * @author Costin@eng.sun.com
 * @author Gal Shachor [shachor@il.ibm.com]
 * @author Yoav Shapira <yoavs@apache.org>
 */
public class LeaderFollowerEndpoint extends PoolTcpEndpoint { // implements Endpoint {

    private final Object threadSync = new Object();

    private ServerSocketFactory factory;

    
    // ------ Leader follower fields

    
    TcpConnectionHandler handler;
    ThreadPoolRunnable listener;
    ThreadPool tp;

    
    // ------ Master slave fields

    /* The background thread. */
    //private Thread thread = null;
    /* Available processors. */
    private Stack workerThreads = new Stack();

    
    public LeaderFollowerEndpoint() {
	tp = new ThreadPool();
    }

    public LeaderFollowerEndpoint( ThreadPool tp ) {
        this.tp=tp;
    }

    // -------------------- Configuration --------------------

    public void setMaxThreads(int maxThreads) {
	if( maxThreads > 0)
	    tp.setMaxThreads(maxThreads);
    }

    public int getMaxThreads() {
        return tp.getMaxThreads();
    }

    public void setMaxSpareThreads(int maxThreads) {
	if(maxThreads > 0) 
	    tp.setMaxSpareThreads(maxThreads);
    }

    public int getMaxSpareThreads() {
        return tp.getMaxSpareThreads();
    }

    public void setMinSpareThreads(int minThreads) {
	if(minThreads > 0) 
	    tp.setMinSpareThreads(minThreads);
    }

    public int getMinSpareThreads() {
        return tp.getMinSpareThreads();
    }

    public void setThreadPriority(int threadPriority) {
      tp.setThreadPriority(threadPriority);
    }

    public int getThreadPriority() {
      return tp.getThreadPriority();
    }

    public void setServerSocketFactory(  ServerSocketFactory factory ) {
	    this.factory=factory;
    }

   ServerSocketFactory getServerSocketFactory() {
 	    return factory;
   }

   public String getStrategy() {
        return "lf";
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
        tp.start();
        running = true;
        paused = false;
        listener = new LeaderFollowerWorkerThread(this);
        tp.runIt(listener);
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
            tp.shutdown();
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
