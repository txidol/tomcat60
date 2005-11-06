package org.apache.coyote.adapters;

import java.util.List;
import java.util.ArrayList;

import org.apache.coyote.Adapter;
import org.apache.coyote.Request;
import org.apache.coyote.Response;

/**
 * Used to collect statistics to evaluate performance of the coyote layer.
 * 
 */
public class Counters implements Adapter {
    
    // per thread
    public static class CountData {
        public long time;
        public long requests;
        public int exceptions;
    }

    // quick hack - need to move the per-thread code from tomcat
    List counters=new ArrayList();
    ThreadLocal tl=new ThreadLocal();
    
    Adapter next;
    
    public Counters() {
    }
    
    public void setNext( Adapter adapter ) {
        next=adapter;
    }
    
    public Adapter getNext( ) {
        return next;
    }

    public void service(Request req, final Response res) throws Exception {
        long t0=System.currentTimeMillis();
        CountData cnt=(CountData)tl.get();
        if( cnt == null ) {
            cnt=new CountData();
            counters.add( cnt );
            tl.set( cnt );
            // TODO: deal with thread death
        }
        
        cnt.requests++;
        try {
            next.service(req,res);
        } catch( Exception ex ) {    
            cnt.exceptions++;
            throw ex;
        } finally {
            long t1=System.currentTimeMillis();            
            cnt.time+=( t1-t0);            
        }
        
    }

    /** Returns statistics for the server.
     *  TODO: make it per thread, agregate all threads
     *  
     * @return
     */
    public CountData getCounts() {
        CountData total=new CountData();
        for( int i=0; i< counters.size(); i++ ) {
            CountData cd=((CountData)counters.get(i));
            total.requests+= cd.requests;
            total.time+=cd.time;
            total.exceptions+=cd.exceptions;
        }
        return total;
    }
}