package org.apache.coyote.standalone;

import java.util.Enumeration;
import java.util.Hashtable;

import org.apache.coyote.Adapter;
import org.apache.coyote.adapters.Counters;
import org.apache.coyote.adapters.HelloWorldAdapter;
import org.apache.coyote.adapters.MapperAdapter;
import org.apache.coyote.http11.Http11BaseProtocol;
import org.apache.tomcat.util.http.mapper.Mapper;
import org.apache.tomcat.util.loader.Loader;
import org.apache.tomcat.util.loader.Repository;


/** 
 * Simple example of embeding coyote.
 * 
 */
public class Main  {
    
    protected Http11BaseProtocol proto;
    protected MapperAdapter mainAdapter;
    
    public Main() {        
    }

    public Http11BaseProtocol getProtocol() {
        return proto;
    }
    
    public void init() {
        proto = new Http11BaseProtocol();

        mainAdapter = new MapperAdapter();        
        mainAdapter.addAdapter("/hello", new HelloWorldAdapter());

        Counters cnt=new Counters();
        cnt.setNext( mainAdapter );

        //proto.setAdapter(mainAdapter);
        proto.setAdapter(cnt);
    }
    
    public MapperAdapter getMapper() {
        return mainAdapter;
    }
    
    /**
     */
    public void run() {
        init();
        start();
    }
    
    public void start() {
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
   
    /** Load the handler table. Just a hack, I'll find a better solution
     */
    public void initHandlers() {

        Hashtable prefixMap=new Hashtable();
        Enumeration keys=System.getProperties().keys();

        Loader loader=null;
        if( loader == null ) {
            // Not started from loader, we are embedded - create the loader, so we
            // can do reloading.
            //LoaderProperties.setPropertiesFile("");
            try {
                loader=new Loader();
                ClassLoader myL=this.getClass().getClassLoader();
                loader.setParentClassLoader( myL );
                loader.init();
            } catch( Throwable t ) {
                t.printStackTrace();
            }
        }

        Repository sR=loader.getRepository("shared");
        // Construct handlers. Handlers will be created, they can get the protocol
        // if they need additional init

        while( keys.hasMoreElements()) {
            String n=(String)keys.nextElement();
            if( n.startsWith("handler.")) {
                String cls=System.getProperty( n );
                String map=n.substring(8);
                Adapter hC=null;
                try {
                    // use the loader's server common repository
                    Class c=sR.getClassLoader().loadClass(cls);
                    //Class c=Class.forName(cls);
                    hC=(Adapter)c.newInstance();
                    prefixMap.put( map, hC );
                } catch( Throwable t ) {
                    t.printStackTrace();
                }
            }
        }
    }

    
    // ------------------- Main ---------------------
    public static void main( String args[]) {
        Main sa=new Main();
        sa.run();
    }

    
}