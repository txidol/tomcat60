package org.apache.coyote.servlet;

import java.io.IOException;

import javax.servlet.Servlet;

import org.apache.coyote.ActionCode;
import org.apache.coyote.Adapter;
import org.apache.coyote.Request;
import org.apache.coyote.Response;
import org.apache.coyote.servlet.util.MappingData;
import org.apache.coyote.servlet.util.MessageWriter;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;

/** Main adapter - top mapping and adaptation.
 * 
 * This handles host and context mapping - the data structures are stored
 * in this class. 
 * 
 * All context-specific mapping is done in the Mapper class, one per
 * context. Mapper should also handle filters and authentication.
 */
public class CoyoteAdapter implements Adapter {
    WebappContextMapper hostMapper = new WebappContextMapper();
    
    private static org.apache.commons.logging.Log log=
        org.apache.commons.logging.LogFactory.getLog( CoyoteAdapter.class );


    public CoyoteAdapter() {
    }
    
    public static int REQUEST_MAPPING_NOTE = 4;

    /** Override the default host mapper.
     *  This allow fine tunning for very large number of domains,
     *  dynamic domain names, large/deep/atypical context names,
     *  dynamic context names. 
     * 
     * @param hmapper
     */
    public void setHostMapper(WebappContextMapper hmapper) {
        hostMapper = hmapper;
    }
    
    public WebappContextMapper getHostMapper() {
        return hostMapper;
    }
    
    public void service(Request req, final Response res)
            throws Exception {
        MappingData mapRes = null;
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
            }
            
            mapRes = request.getMappingData(); 
            if(mapRes == null ) {
                mapRes = new MappingData();
                req.setNote(REQUEST_MAPPING_NOTE, mapRes);
            }
            hostMapper.mapContext(req.remoteHost(), req.decodedURI(), mapRes);
            ServletContextImpl ctx = (ServletContextImpl)mapRes.context;
            if( ctx == null ) {
                // TODO: 404
                return;
            }
            WebappServletMapper mapper = ctx.getMapper();
            mapper.map(req.decodedURI(), mapRes);
            
            ServletConfigImpl h=(ServletConfigImpl)mapRes.wrapper;
            if (h != null) {
                serviceServlet( req, request, response, h, mapRes );
            }
        } catch( Throwable t ) {
            t.printStackTrace(System.out);
        } finally {
            if(mapRes != null ) 
                mapRes.recycle();
        }

        // Final processing
        MessageWriter.getWriter(req, res, 0).flush();
        res.finish();

        req.recycle();
        res.recycle();

    }
    
    public static final int ADAPTER_NOTES = 1;

    
    /** Coyote / mapper adapter. Result of the mapper.
     *  
     *  This replaces the valve chain, the path is: 
     *    1. coyote calls mapper -> result Adapter 
     *    2. service is called. Additional filters are set on the wrapper. 
     * @param mapRes 
     */
    public void serviceServlet(Request req, ServletRequestImpl request, 
                               ServletResponseImpl response,
                               ServletConfigImpl servletConfig, MappingData mapRes) 
        throws IOException {
        
        try {

            // Parse and set Catalina and configuration specific 
            // request parameters
            
//            if ( postParseRequest(req, request, res, response) ) {
//                // Calling the container
//                connector.getContainer().getPipeline().getFirst().invoke(request, response);
//            }
            // Catalina default valves :
            // Find host/context
            // apply auth filters
            // 
            

            Servlet servlet = servletConfig.allocate();
            WebappFilterMapper filterMap = servletConfig.getParent().getFilterMapper();
            FilterChainImpl chain = 
                filterMap.createFilterChain(request, servletConfig, servlet);
            
            if (chain == null) {
                servlet.service(request, response);
            } else {
                chain.doFilter(request, response);
            }
            
            response.finishResponse();
            req.action( ActionCode.ACTION_POST_REQUEST , null);

        } catch (IOException e) {
            ;
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            // Recycle the wrapper request and response
            request.recycle();
            response.recycle();
        }
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

    // --------------------------------------------------------- Public Methods
}