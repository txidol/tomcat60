package org.apache.coyote.adapters;

import java.util.Enumeration;
import java.util.Hashtable;

import org.apache.coyote.Adapter;
import org.apache.coyote.Request;
import org.apache.coyote.Response;
import org.apache.coyote.http11.Http11BaseProtocol;
import org.apache.coyote.standalone.MessageWriter;
import org.apache.tomcat.util.loader.Loader;
import org.apache.tomcat.util.loader.Repository;

/**
 * Very, very simple mapper for standalone coyote. Used to test and experiment
 * various low level changes, or for very simple http servers.
 * 
 * It currently supports only prefix mapping, using the first url component.
 */
public class Mapper implements Adapter {
    
    // TODO: add extension mappings 
    // Key = prefix, one level only, value= class name of Adapter
    // key starts with a / and has no other / ( /foo - but not /foo/bar )
    Hashtable prefixMap=new Hashtable();

    String fileAdapterCN="org.apache.coyote.adapters.FileAdapter";
    Adapter defaultAdapter=new FileAdapter();    

    public Mapper() {
    }

    public void service(Request req, final Response res)
    throws Exception {
        try {           
            String uri=req.requestURI().toString();
            if( uri.equals("/") ) uri="index.html";
            String ctx="";
            String local=uri;
            if( uri.length() > 1 ) {
                int idx=uri.indexOf('/', 1);
                if( idx > 0 ) {
                    ctx=uri.substring(0, idx);
                    local=uri.substring( idx );
                }
            }
            Adapter h=(Adapter)prefixMap.get( ctx );
            if( h != null ) {
                h.service( req, res );
            } else {
                defaultAdapter.service( req, res );
            }
        } catch( Throwable t ) {
            t.printStackTrace();
        } 

        //out.flushBuffer();
        //out.getByteChunk().flushBuffer(); - part of res.finish()
        // final processing
        MessageWriter.getWriter(req, res, 0).flush();
        res.finish();

        req.recycle();
        res.recycle();

    }

    /** Load the handler table. Just a hack, I'll find a better solution
     */
    public void initHandlers() {

        prefixMap=new Hashtable();
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

    public void addAdapter( String prefix, Adapter adapter ) {
        prefixMap.put(prefix, adapter);
    }
    
    public void setDefaultAdapter(Adapter adapter) {
        defaultAdapter=adapter;
    }

    public Adapter getDefaultAdapter() {
        return defaultAdapter;
    }

}