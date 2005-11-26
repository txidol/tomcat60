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
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

import org.apache.tomcat.util.threads.ThreadPool;
import org.apache.tomcat.util.threads.ThreadPoolRunnable;
import org.apache.tomcat.util.threads.ThreadWithAttributes;


/** All threads blocked in accept(). New thread created on demand.
 * No use of ThreadPool or ServerSocketFactory.
 * 
 * 
 */
public class NioEndpoint extends SimpleEndpoint { 

    private final Object threadSync = new Object();

    // active acceptors
    private int acceptors=0;
    
    ThreadPool tp;
    
    public NioEndpoint() {
        tp=new ThreadPool();
        tp.setMinSpareThreads(2);
        tp.setMaxSpareThreads(8);
    }

    // -------------------- Configuration --------------------
    // -------------------- Thread pool --------------------

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

    public void setDaemon(boolean b) {
        daemon=b;
        tp.setDaemon( b );
    }
    
    public boolean getDaemon() {
        return tp.getDaemon();
    }

    public String getName() {
        return tp.getName();
    }

    public void setName(String name) {
        tp.setName(name);
    }

    
    // ---------------------- 
    public String getStrategy() {
        return "nio";
    }
    
    public int getCurrentThreadsBusy() {
        return curThreads;
    }
    
    // -------------------- Public methods --------------------
    
    public void initEndpoint() throws IOException, InstantiationException {
        try {
            if(serverSocket==null) {
                try {
                    ServerSocketChannel ssc=ServerSocketChannel.open();
                    serverSocket = ssc.socket();
                    SocketAddress sa = null;
                    if (inet == null) {
                        sa = new InetSocketAddress( port );
                    } else {
                        sa = new InetSocketAddress(inet, port);
                    }
                    serverSocket.bind( sa , backlog);
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
        if( maxSpareThreads == minSpareThreads ) {
            maxSpareThreads = minSpareThreads + 4;
        }
        running = true;
        paused = false;
        
        tp.start();
        try {
            selector = Selector.open();
        } catch (IOException e) {
            e.printStackTrace();
        }
        addSocketAccept( serverSocket, new SocketDispatch());
        Thread poller = new Thread( new PollerThread());
        poller.start();
    }


    // -------------------------------------------------- Master Slave Methods

    


    public boolean getPolling() {
        return true;
    }
    
    public void addPolling(Socket s, Object context ) {
        
    }

    
    public void run() {
        // nothing 
    }
    

    public void addSocketRead(Socket s, Object o) throws IOException {
        s.getChannel().register( selector, SelectionKey.OP_READ, o);        
    }
    
    public void addSocketAccept( ServerSocket ss, Object o) throws IOException {
        ServerSocketChannel ssc=ss.getChannel();
        ssc.configureBlocking(false);
        ssc.register( selector, SelectionKey.OP_ACCEPT, o);
    }

    Selector selector;
    
    /** Uses NIO to implment selection.
     *  In addition to sockets, you can add other kind of objects.
     *  
     * @author Costin Manolache
     */
    class PollerThread implements Runnable {

        public PollerThread() {
        }
               
        public void run() {
            while( running ) {
                
                try {
                    int selRes = selector.select();

                    if( selRes == 0 ) {
                        System.err.println("Select with 0 keys " + 
                                selector.keys().size() );
                        for( SelectionKey k : selector.keys() ) {
                            System.err.println("K " + k.interestOps() +
                                    " " + k.readyOps() + " " + k.toString() + " "
                                    + k.isValid() );
                        }
                        continue;
                    }
                    
                    Set selected = selector.selectedKeys();
                    Iterator selI = selected.iterator();
                    
                    while( selI.hasNext() ) {
                        SelectionKey sk = (SelectionKey)selI.next();
                        selI.remove();
                        Object skAt = sk.attachment();
                        
                        int readyOps = sk.readyOps();
                        SelectableChannel sc = sk.channel();
                        
                        // TODO: use the attachment to decide what's to do.
                        if( sk.isAcceptable() ) {
                            ServerSocketChannel ssc=(ServerSocketChannel)sc;
                            SocketChannel sockC = ssc.accept();
                            
                            
                            // process the connection in the thread pool
                            if( skAt instanceof ThreadPoolRunnable ) {
                                tp.runIt( (ThreadPoolRunnable) skAt, sockC);
                            }
                            //sk.interestOps( sk.interestOps() | 
                            //        SelectionKey.OP_ACCEPT );
                            System.err.println( sk.interestOps() ); 

                            continue;
                        }

                        // TODO: this is for keep alive
                        if( sk.isReadable() ) {
                            SocketChannel sockC = (SocketChannel)sc;
                            
                            // Incoming data on keep-alive connection.
                            continue;
                        }
                        
                        // dispatch the socket to a pool thread
                        System.err.println("Select: " + readyOps);
                    }
                    
                } catch (IOException e) {
                    e.printStackTrace();
                }
                
            }
            
        }
        
    }
    
    class SocketDispatch implements ThreadPoolRunnable {

        public Object[] getInitData() {
            // no synchronization overhead, but 2 array access 
            Object obj[]=new Object[2];
            obj[1]= getConnectionHandler().init();
            obj[0]=new TcpConnection();
            return obj;
        }
        
        public void runIt(Object perThrData[]) {
            ThreadWithAttributes t=(ThreadWithAttributes)Thread.currentThread();
            
            SocketChannel sc=(SocketChannel)t.getParam(tp);
            if (isRunning()) {
                // Loop if endpoint is paused
                while (isPaused()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                if (null != sc) {
                    processSocket(sc.socket(), (TcpConnection) perThrData[0], 
                            (Object[]) perThrData[1]);
                }

            }
        }
        
    }

}
