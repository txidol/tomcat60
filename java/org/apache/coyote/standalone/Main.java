package org.apache.coyote.standalone;

import org.apache.coyote.adapters.Counters;
import org.apache.coyote.adapters.HelloWorldAdapter;
import org.apache.coyote.adapters.Mapper;
import org.apache.coyote.http11.Http11BaseProtocol;


/** 
 * Simple example of embeding coyote.
 * 
 */
public class Main  {
    
    protected Http11BaseProtocol proto;
    protected Mapper mainAdapter;
    
    public Main() {        
    }

    public Http11BaseProtocol getProtocol() {
        return proto;
    }
    
    public void init() {
        proto = new Http11BaseProtocol();

        mainAdapter = new Mapper();        
        mainAdapter.addAdapter("/hello", new HelloWorldAdapter());

        Counters cnt=new Counters();
        cnt.setNext( mainAdapter );

        //proto.setAdapter(mainAdapter);
        proto.setAdapter(cnt);
    }
    
    /**
     */
    public void run() {
        init();
        
        if( proto.getPort() == 0 )
            proto.setPort(8800);
        
        try {
            proto.init();

            proto.getThreadPool().setDaemon(false);
            
            proto.start();

        } catch (Exception e) {
            e.printStackTrace();
        }

    }
    
    // ------------------- Main ---------------------
    public static void main( String args[]) {
        Main sa=new Main();
        sa.run();
    }

    
}