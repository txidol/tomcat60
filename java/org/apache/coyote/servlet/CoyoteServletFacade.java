/*
 */
package org.apache.coyote.servlet;

import java.io.IOException;
import java.util.HashMap;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.apache.coyote.ActionCode;
import org.apache.coyote.Adapter;
import org.apache.coyote.Request;
import org.apache.coyote.Response;
import org.apache.coyote.adapters.FileAdapter;
import org.apache.coyote.http11.Http11Protocol;
import org.apache.coyote.standalone.MessageWriter;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.mapper.Mapper;
import org.apache.tomcat.util.http.mapper.MappingData;
import org.apache.tomcat.util.res.StringManager;

/**
 * Frontend for a minimal servlet impl for coyote.
 * 
 * This is based on catalina classes, with non-essential features removed.
 * It is possible to add back some of the flexibility using callbacks/hooks, but
 * without requiring additional code - this package should be a sufficient 
 * implementation for a working servlet container.
 * 
 * @author Costin Manolache
 */
public class CoyoteServletFacade {
    static CoyoteServletFacade facade = new CoyoteServletFacade();

    protected HashMap hosts = new HashMap(); 
    protected Http11Protocol proto;
    Mapper mapper = new Mapper();
    Mapper authMapper = new Mapper();
    
    String hostname = ""; // current hostname, used for settings
    
    protected CoyoteServletProcessor mainAdapter;
    FileAdapter fa = new FileAdapter();
    
    
    private CoyoteServletFacade() {
        proto = new Http11Protocol();

        mainAdapter = new CoyoteServletProcessor(mapper);        

        //Counters cnt=new Counters();
        //cnt.setNext( mainAdapter );
        proto.setAdapter(mainAdapter);
        //proto.setAdapter(cnt);

        //mapper.addWrapper("*", fa);
        mapper.setDefaultHostName("localhost");

    }
    
    public static CoyoteServletFacade getServletImpl() {
        // TODO: restrict access to only first call.
        return facade;
    }
    
    public void initHttp(int port) {
        proto.setPort(port);
    }

    public void start() {
        if( proto.getPort() == 0 ) { //&& 
                //proto.getEndpoint().getServerSocket() == null) {
            proto.setPort(8800);
        }
        
        try {
            proto.init();

            proto.getEndpoint().setDaemon(false);
            
            proto.start();

        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
    
    // TODO: make sure the mapper is the only one holding references to any
    // of the objects - better reloading

    /**
     * 
     * @param hostname - "" if default host, or string to be matched with Host header
     * @param path - context path, "/" for root, "/examples", etc
     * @return a servlet context
     */
    public ServletContext createServletContext(String hostname, String path) {
        Host host = (Host)hosts.get(hostname);
        if( host == null ) {
            host = new Host();
            host.setName(hostname);
            hosts.put(hostname, host);
            mapper.addHost(hostname, new String[] {}, host);
        }
        ServletContextImpl ctx = new ServletContextImpl(path);
        ctx.setParent(host);
        ctx.setPath(path);
        
        // TODO: read web.xml or equivalent
        
        // TODO: defaults 
        // 
        mapper.addContext(hostname, path, ctx, new String[] {"index.html"}, 
                null);
        mapper.addWrapper(hostname, path, "/", fa);
        host.addChild(ctx);
        return ctx;
    }

    // -------------- Web.xml reader will call this ---------
    // For specialized cases - you can call this directly
    // Experiment: WebXmlConfig to call this or generate properties, then
    // at run time read the properties.
    
    public void setContextParams(ServletContext ctx, HashMap params) {
        ((ServletContextImpl)ctx).setContextParameters(params);
    }

    public ServletConfig createServletWrapper(ServletContext ctx, 
            String name, Servlet servlet) throws ServletException {
        ServletConfigImpl w = new ServletConfigImpl();
        // TODO: postpone to first use
        // TODO: grab config
        servlet.init(w);
        w.setServlet(servlet);
        w.setParent((ServletContextImpl)ctx);
        return w;
    }

    public void addMapping(String path, ServletConfig wrapper) {
        ServletContextImpl ctx = (ServletContextImpl)wrapper.getServletContext();
        Host host = (ctx).getParent();
        mapper.addWrapper(host.getName(), ctx.getPath(), path, 
                new CoyoteServletAdapter(wrapper));
    }
    
    // TODO: auth
    
    public static class CoyoteServletProcessor implements Adapter {
        private Mapper mapper=new Mapper();
      
        public CoyoteServletProcessor(Mapper mapper2) {
            mapper = mapper2;
        }

        public void service(Request req, final Response res)
                throws Exception {
            try {
                
                MessageBytes decodedURI = req.decodedURI();
                decodedURI.duplicate(req.requestURI());

                if (decodedURI.getType() == MessageBytes.T_BYTES) {
                    // %xx decoding of the URL
                    try {
                        req.getURLDecoder().convert(decodedURI, false);
                    } catch (IOException ioe) {
                        res.setStatus(400);
                        res.setMessage("Invalid URI");
                        throw ioe;
                    }
                    // Normalization
                    if (!normalize(req.decodedURI())) {
                        res.setStatus(400);
                        res.setMessage("Invalid URI");
                        return;
                    }
                    // Character decoding
                    //convertURI(decodedURI, request);
                } else {
                    // The URL is chars or String, and has been sent using an in-memory
                    // protocol handler, we have to assume the URL has been properly
                    // decoded already
                    decodedURI.toChars();
                }


                
                // TODO: per thread data - does it help ? 
                
                MappingData mapRes = new MappingData();
                mapper.map(req.remoteHost(), req.decodedURI(), 
                        mapRes);
                
                Adapter h=(Adapter)mapRes.wrapper;
                if (h != null) {
                    h.service( req, res );
                }
                
            } catch( Throwable t ) {
                t.printStackTrace(System.out);
            } 

            // Final processing
            MessageWriter.getWriter(req, res, 0).flush();
            res.finish();

            req.recycle();
            res.recycle();

        }
        
        /**
         * Normalize URI.
         * <p>
         * This method normalizes "\", "//", "/./" and "/../". This method will
         * return false when trying to go above the root, or if the URI contains
         * a null byte.
         * 
         * @param uriMB URI to be normalized
         */
        public static boolean normalize(MessageBytes uriMB) {

            ByteChunk uriBC = uriMB.getByteChunk();
            byte[] b = uriBC.getBytes();
            int start = uriBC.getStart();
            int end = uriBC.getEnd();

            // URL * is acceptable
            if ((end - start == 1) && b[start] == (byte) '*')
              return true;

            int pos = 0;
            int index = 0;

            // Replace '\' with '/'
            // Check for null byte
            for (pos = start; pos < end; pos++) {
                if (b[pos] == (byte) '\\')
                    b[pos] = (byte) '/';
                if (b[pos] == (byte) 0)
                    return false;
            }

            // The URL must start with '/'
            if (b[start] != (byte) '/') {
                return false;
            }

            // Replace "//" with "/"
            for (pos = start; pos < (end - 1); pos++) {
                if (b[pos] == (byte) '/') {
                    while ((pos + 1 < end) && (b[pos + 1] == (byte) '/')) {
                        copyBytes(b, pos, pos + 1, end - pos - 1);
                        end--;
                    }
                }
            }

            // If the URI ends with "/." or "/..", then we append an extra "/"
            // Note: It is possible to extend the URI by 1 without any side effect
            // as the next character is a non-significant WS.
            if (((end - start) >= 2) && (b[end - 1] == (byte) '.')) {
                if ((b[end - 2] == (byte) '/') 
                    || ((b[end - 2] == (byte) '.') 
                        && (b[end - 3] == (byte) '/'))) {
                    b[end] = (byte) '/';
                    end++;
                }
            }

            uriBC.setEnd(end);

            index = 0;

            // Resolve occurrences of "/./" in the normalized path
            while (true) {
                index = uriBC.indexOf("/./", 0, 3, index);
                if (index < 0)
                    break;
                copyBytes(b, start + index, start + index + 2, 
                          end - start - index - 2);
                end = end - 2;
                uriBC.setEnd(end);
            }

            index = 0;

            // Resolve occurrences of "/../" in the normalized path
            while (true) {
                index = uriBC.indexOf("/../", 0, 4, index);
                if (index < 0)
                    break;
                // Prevent from going outside our context
                if (index == 0)
                    return false;
                int index2 = -1;
                for (pos = start + index - 1; (pos >= 0) && (index2 < 0); pos --) {
                    if (b[pos] == (byte) '/') {
                        index2 = pos;
                    }
                }
                copyBytes(b, start + index2, start + index + 3,
                          end - start - index - 3);
                end = end + index2 - index - 3;
                uriBC.setEnd(end);
                index = index2;
            }

            //uriBC.setBytes(b, start, end);
            uriBC.setEnd(end);
            return true;

        }

        
        /**
         * Copy an array of bytes to a different position. Used during 
         * normalization.
         */
        protected static void copyBytes(byte[] b, int dest, int src, int len) {
            for (int pos = 0; pos < len; pos++) {
                b[pos + dest] = b[pos + src];
            }
        }

        public boolean event(Request req, Response res, boolean error) throws Exception {
            // TODO Auto-generated method stub
            return false;
        }

    }
    
    public static class CoyoteServletAdapter implements Adapter {
        static StringManager sm = StringManager.getManager("org.apache.coyote.servlet");

        private static org.apache.commons.logging.Log log=
            org.apache.commons.logging.LogFactory.getLog( CoyoteServletAdapter.class );

        
        public static final int ADAPTER_NOTES = 1;

        ServletConfigImpl servletConfig;
        
        public CoyoteServletAdapter( ServletConfig cfg ) {
            this.servletConfig = (ServletConfigImpl)cfg;
        }
        
        
        /** Coyote / mapper adapter. Result of the mapper.
         *  
         *  This replaces the valve chain, the path is: 
         *    1. coyote calls mapper -> result Adapter 
         *    2. service is called. Additional filters are set on the wrapper. 
         */
        public void service(org.apache.coyote.Request req, org.apache.coyote.Response res) 
            throws IOException {
            
            ServletRequestImpl request = (ServletRequestImpl) req.getNote(ADAPTER_NOTES);
            ServletResponseImpl response = (ServletResponseImpl) res.getNote(ADAPTER_NOTES);

            if (request == null) {

                // Create objects
                request = new ServletRequestImpl();
                request.setCoyoteRequest(req);
                response = new ServletResponseImpl();
                response.setRequest(request);
                response.setCoyoteResponse(res);

                // Link objects
                request.setResponse(response);

                // Set as notes
                req.setNote(ADAPTER_NOTES, request);
                res.setNote(ADAPTER_NOTES, response);

                // Set query string encoding
//                req.getParameters().setQueryStringEncoding
//                    (connector.getURIEncoding());

            }

            try {

                // Parse and set Catalina and configuration specific 
                // request parameters
//                if ( postParseRequest(req, request, res, response) ) {
//                    // Calling the container
//                    connector.getContainer().getPipeline().getFirst().invoke(request, response);
//                }
                // Catalina default valves :
                // Find host/context
                // apply auth filters
                // 
                

                Servlet servlet = servletConfig.allocate();
                
                servlet.service(request, response);
                
                response.finishResponse();
                req.action( ActionCode.ACTION_POST_REQUEST , null);

            } catch (IOException e) {
                ;
            } catch (Throwable t) {
                log.error(sm.getString("coyoteAdapter.service"), t);
            } finally {
                // Recycle the wrapper request and response
                request.recycle();
                response.recycle();
            }

        }


        public boolean event(Request req, Response res, boolean error) throws Exception {
            // TODO Auto-generated method stub
            return false;
        }

    }

}
