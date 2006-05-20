package org.apache.coyote.adapters;

import java.util.Hashtable;

import org.apache.coyote.Adapter;
import org.apache.coyote.Request;
import org.apache.coyote.Response;
import org.apache.coyote.standalone.MessageWriter;
import org.apache.tomcat.util.http.mapper.Mapper;
import org.apache.tomcat.util.http.mapper.MappingData;

/**
 * 
 */
public class MapperAdapter implements Adapter {

    public Mapper mapper=new Mapper();
    private Adapter defaultAdapter;
    
    public MapperAdapter() {
        mapper = new Mapper();
    }

    public MapperAdapter(Mapper mapper2) {
        mapper = mapper2;
    }

    public void service(Request req, final Response res)
            throws Exception {
        try {
            MappingData mapRes = new MappingData();
            mapper.map(req.remoteHost(), req.decodedURI(), mapRes);
            
            Adapter h=(Adapter)mapRes.wrapper;
            if (h != null) {
                h.service( req, res );
            }
            
        } catch( Throwable t ) {
            t.printStackTrace();
        } 

        // Final processing
        MessageWriter.getWriter(req, res, 0).flush();
        res.finish();

        req.recycle();
        res.recycle();

    }


    public void addAdapter( String path, Adapter adapter ) {
        mapper.addWrapper(path, adapter);
    }
    
    public void setDefaultAdapter(Adapter adapter) {
        mapper.addWrapper("*", adapter);
        defaultAdapter = adapter;
    }
    
    public Adapter getDefaultAdapter() {
        return defaultAdapter;
    }

    public boolean event(Request req, Response res, boolean error) throws Exception {
        // TODO Auto-generated method stub
        return false;
    }

}