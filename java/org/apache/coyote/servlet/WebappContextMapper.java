package org.apache.coyote.servlet;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.apache.coyote.servlet.WebappServletMapper.ContextMapElement;
import org.apache.coyote.servlet.util.MappingData;
import org.apache.tomcat.util.buf.CharChunk;
import org.apache.tomcat.util.buf.MessageBytes;

/** 
 * This handles host and context mapping - the data structures are stored
 * in this class. 
 * 
 * All context-specific mapping is done in the Mapper class, one per
 * context. Mapper should also handle filters and authentication.
 * 
 * You can extend and override the mapper in MapperAdapter.
 */
public class WebappContextMapper implements Filter {
    
    // TODO: compare toString + lowercase + hash with 
    //  list[] + ignore case and sorted list and ignore case and also
    //  a tree. 
    // For the simple container - most of the time only the default host will
    // be used.
    
    /** String lowercase(hostname) -> Host object
     *  All aliases are included.
     */
    Map hostMap = new HashMap();
    
    // If no host or alias matches
    Host defaultHost = new Host();
    
    private static org.apache.commons.logging.Log log=
        org.apache.commons.logging.LogFactory.getLog( WebappContextMapper.class );


    public WebappContextMapper() {
        defaultHost.setName("");
        hostMap.put("", defaultHost);
    }
        /**
     * Add a new host to the mapper.
     *
     * @param name Virtual host name
     * @param host Host object
     */
    public void addHost(String name, String[] aliases, Host host) {
        hostMap.put(name.toLowerCase(), host);
        for (int i = 0; i < aliases.length; i++) {
            hostMap.put(aliases[i].toLowerCase(), host);
        }
    }
    
    public Iterator getHosts() {
        return hostMap.values().iterator();
    }

    /**
     * Remove a host from the mapper.
     *
     * @param name Virtual host name
     */
    public void removeHost(String name) {
        Host host = findHost(name);
        if (host == null) {
            return;
        }
        Iterator hostIt = hostMap.entrySet().iterator();
        while( hostIt.hasNext()) {
            Map.Entry entry = (Map.Entry)hostIt.next();
            if(entry.getValue() == host) {
                hostIt.remove();
            }
        }
    }

    /**
     * Add a new Context to an existing Host.
     *
     * @param hostName Virtual host name this context belongs to
     * @param contextPath Context path
     * @param context Context object
     * @param welcomeResources Welcome files defined for this context
     * @param resources Static resources of the context
     */
    public void addContext
        (String hostName, ServletContextImpl context,
         String[] welcomeResources, File resources) 
        throws ServletException
    {
        Host host = findHost(hostName);
        if (host == null) throw new ServletException("Host not found");
        String path = context.getContextPath();
        int slashCount = WebappServletMapper.slashCount(path);
        synchronized (host) {
                ContextMapElement[] contexts = host.contexts;
                // Update nesting
                if (slashCount > host.nesting) {
                    host.nesting = slashCount;
                }
                ContextMapElement[] newContexts = new ContextMapElement[contexts.length + 1];
                ContextMapElement newContext = context.getMapper().contextMapElement;
                newContext.name = path;
                newContext.object = context;
                newContext.welcomeResources = welcomeResources;
                newContext.resources = resources;
                if (WebappServletMapper.insertMap(contexts, newContexts, newContext)) {
                    host.contexts = newContexts;
                }
            }
    }


    /**
     * Remove a context from an existing host.
     *
     * @param hostName Virtual host name this context belongs to
     * @param path Context path
     */
    public void removeContext(String hostName, String path) 
            throws ServletException {
        Host host = findHost(hostName);
        if (host == null) throw new ServletException("Host not found");
        if (host.name.equals(hostName)) {
            synchronized (host) {
                ContextMapElement[] contexts = host.contexts;
                if( contexts.length == 0 ){
                    return;
                }
                ContextMapElement[] newContexts = new ContextMapElement[contexts.length - 1];
                if (WebappServletMapper.removeMap(contexts, newContexts, path)) {
                    host.contexts = newContexts;
                    // Recalculate nesting
                    host.nesting = 0;
                    for (int i = 0; i < newContexts.length; i++) {
                        int slashCount = WebappServletMapper.slashCount(newContexts[i].name);
                        if (slashCount > host.nesting) {
                            host.nesting = slashCount;
                        }
                    }
                }
            }
        }
    }


    /**
     * Map the specified host name and URI, mutating the given mapping data.
     *
     * @param host Virtual host name
     * @param uri URI
     * @param mappingData This structure will contain the result of the mapping
     *                    operation
     */
    public void mapContext(MessageBytes host, MessageBytes uri,
                           MappingData mappingData)
        throws Exception {

        if (host.isNull()) {
            host.getCharChunk().append(defaultHost.getName());
        }
        host.toChars();
        uri.toChars();
        internalMap(host.getCharChunk(), uri.getCharChunk(), mappingData);

    }

    // -------------------------------------------------------- Private Methods

    /** Find a the host. 
     *  Override for corner cases ( wildcards, huge number of hosts, 
     *  special patterns, dynamic behavior ).
     *  
     */
    public Host findHost(String hostName) {
        Host host = null;
        if(hostMap.size() > 0 ) {
            // don't bother if we only have default host
            host = (Host)hostMap.get(hostName.toLowerCase());
        }
        if (host == null) host = defaultHost;
        return host;
    }
    
    /**
     * Map the specified URI.
     */
    private final void internalMap(CharChunk host, CharChunk uri,
                                   MappingData mappingData)
        throws Exception {

        uri.setLimit(-1);

        // Virtual host mapping
        if (mappingData.host == null) {
            mappingData.host = findHost(host.toString());
        }
        if (mappingData.host == null) {
            throw new ServletException("Host not found");
        }

        ContextMapElement[] contexts = null;
        ContextMapElement context = null;
        int nesting = 0;

        contexts = ((Host)mappingData.host).contexts;
        nesting = ((Host)mappingData.host).nesting;

        // Context mapping
        if (mappingData.context == null) {
            int pos = WebappServletMapper.find(contexts, uri);
            if (pos == -1) {
                return;
            }

            int lastSlash = -1;
            int uriEnd = uri.getEnd();
            int length = -1;
            boolean found = false;
            while (pos >= 0) {
                if (uri.startsWith(contexts[pos].name)) {
                    length = contexts[pos].name.length();
                    if (uri.getLength() == length) {
                        found = true;
                        break;
                    } else if (uri.startsWithIgnoreCase("/", length)) {
                        found = true;
                        break;
                    }
                }
                if (lastSlash == -1) {
                    lastSlash = WebappServletMapper.nthSlash(uri, nesting + 1);
                } else {
                    lastSlash = WebappServletMapper.lastSlash(uri);
                }
                uri.setEnd(lastSlash);
                pos = WebappServletMapper.find(contexts, uri);
            }
            uri.setEnd(uriEnd);

            if (!found) {
                if (contexts[0].name.equals("")) {
                    context = contexts[0];
                }
            } else {
                context = contexts[pos];
            }
            if (context != null) {
                mappingData.context = context.object;
                mappingData.contextPath.setString(context.name);
            }
        }
    }

    
    public void init(FilterConfig filterConfig) throws ServletException {
    }
    
    public void doFilter(ServletRequest request, 
                         ServletResponse response, 
                         FilterChain chain) 
            throws IOException, ServletException {
    }
    
    public void destroy() {
    }


}