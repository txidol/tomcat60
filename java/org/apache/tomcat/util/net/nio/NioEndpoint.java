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

package org.apache.tomcat.util.net.nio;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.Channel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import org.apache.tomcat.util.net.simple.SimpleEndpoint;


/** All threads blocked in accept(). New thread created on demand.
 * No use of ThreadPool or ServerSocketFactory.
 * 
 * 
 */
public class NioEndpoint extends SimpleEndpoint { 
    private ThreadPoolExecutor tp;
    
    public NioEndpoint() {
        tp=(ThreadPoolExecutor) Executors.newCachedThreadPool();;
        type = "nio";
    }

    // -------------------- Configuration --------------------
    // -------------------- Thread pool --------------------

    public ThreadPoolExecutor getThreadPool() {
        return tp;
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
        
        //tp.start();
        try {
            selector = Selector.open();
        } catch (IOException e) {
            e.printStackTrace();
        }

        PollerThread acceptTask = new PollerThread();
        
        addSocketAccept( serverSocket, acceptTask);
        
        tp.execute(acceptTask);
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
    
    /**
     */
    public ServerSocketChannel getInetdChannel() {
        SelectorProvider sp=SelectorProvider.provider();
        
        try {
            Channel ch=sp.inheritedChannel();
            if(ch!=null ) {
                System.err.println("Inherited: " + ch.getClass().getName());
                ServerSocketChannel ssc=(ServerSocketChannel)ch;
                return ssc;
                //proto.getEndpoint().setServerSocketFactory(new InetdServerSocketFactory(ssc.socket()));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
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
    class PollerThread implements Runnable  {
        
        public void run() {
            try {
                int selRes = selector.select();

                if( selRes == 0 ) {
                    System.err.println("Select with 0 keys " + 
                            selector.keys().size() );
                    Iterator sI = selector.keys().iterator();
                    while (sI.hasNext()) {
                        SelectionKey k = (SelectionKey) sI.next();
                        System.err.println("K " + k.interestOps() +
                                " " + k.readyOps() + " " + k.toString() + " "
                                + k.isValid() );
                    }
                    return;
                }
                
                Set selected = selector.selectedKeys();
                Iterator selI = selected.iterator();
                
                while( selI.hasNext() ) {
                    SelectionKey sk = (SelectionKey)selI.next();
                    selI.remove();
                    //Object skAt = sk.attachment(); // == this
                    
                    int readyOps = sk.readyOps();
                    SelectableChannel sc = sk.channel();
                    
                    // TODO: use the attachment to decide what's to do.
                    if( sk.isAcceptable() ) {
                        ServerSocketChannel ssc=(ServerSocketChannel)sc;
                        SocketChannel sockC = ssc.accept();
                        
                        //  continue accepting on a different thread
                        // Side effect: if pool is full, accept will happen
                        // a bit later. 
                        // TODO: customize this if needed
                        tp.execute( this ); 
                        // now process the socket. 
                        processSocket(sockC.socket());  
                        continue;
                    }

                    // TODO: this is for keep alive
                    if( sk.isReadable() ) {
                        //SocketChannel sockC = (SocketChannel)sc;
                        
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
