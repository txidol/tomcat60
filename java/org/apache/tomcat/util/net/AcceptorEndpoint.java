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
import java.net.Socket;

import org.apache.tomcat.util.threads.ThreadWithAttributes;


/** All threads blocked in accept(). New thread created on demand.
 * No use of ThreadPool or ServerSocketFactory.
 * 
 * 
 */
public class AcceptorEndpoint extends SimpleEndpoint { 

    private final Object threadSync = new Object();

    // ------ Leader follower fields
    
    // ------ Master slave fields

    /* The background thread. */
    private Thread thread = null;

    // active acceptors
    private int acceptors=0;
    
    public AcceptorEndpoint() {
    }

    // -------------------- Configuration --------------------

    public String getStrategy() {
        return "ms";
    }
    
    public void setStrategy(String strategy) {
    }

    public int getCurrentThreadsBusy() {
        return curThreads;
    }
    
    // -------------------- Public methods --------------------

    public void startEndpoint() throws IOException, InstantiationException {
        if (!initialized) {
            initEndpoint();
        }
        if( maxSpareThreads == minSpareThreads ) {
            maxSpareThreads = minSpareThreads + 4;
        }
        running = true;
        paused = false;
        checkSpares();
    }


    // -------------------------------------------------- Master Slave Methods

    

    /** Block in accept. If spares is low, create more spares.
     *  If spares is high - terminate this thread. Checks before 
     *  and after running the connection handler.
     */
    class AcceptorThread implements Runnable {
        private TcpConnection con = new TcpConnection();

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
                
                workerStart(this);
                
                // Process the request from this socket
                processSocket(socket, con, threadData);

                // Finish up this request
                workerDone(this);
                
                if( checkSpares() ) {
                    return;
                }
            }
            
            acceptors--; // we're done
            synchronized (threadSync) {
                threadSync.notifyAll();
            }            
        }        
    }
    
    public void run() {
        // nothing 
    }
    
    public void workerDone(Runnable workerThread) {
        curThreads--;
    }

    void workerStart( Runnable r ) {
        curThreads++;
    }
    
    void newAcceptor() {
        acceptors++;
        Thread t=new ThreadWithAttributes( this, new AcceptorThread());
        if( threadPriority > 0 ) {
            t.setPriority(threadPriority);
        }
        t.setDaemon(daemon);
        threadStart( t );
        t.start();        
    }
    
    /** Check the spare situation. If not enough - create more.
     * If too many - return true to end this.
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
    
}
