/*
 */
package org.apache.coyote.servlet;

import java.io.File;
import java.util.HashMap;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.apache.tomcat.servlets.file.FileServlet;
import org.apache.tomcat.util.http.mapper.Mapper;
import org.apache.tomcat.util.loader.Module;
import org.apache.tomcat.util.loader.Repository;
import org.apache.tomcat.util.net.http11.Http11Protocol;

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
    
    /** Simple interface to be used by manually or generated web.xml
     *  readers.
     */ 
    public static interface WebappInitializer {
        public void initWebapp(CoyoteServletFacade facade, ServletContext ctx) 
          throws ServletException;
    }

    protected HashMap hosts = new HashMap(); 
    protected Http11Protocol proto;
    Mapper mapper = new Mapper();
    Mapper authMapper = new Mapper();
    
    String hostname = ""; // current hostname, used for settings
    
    protected MapperAdapter mainAdapter;
    //FileAdapter fa = new FileAdapter();
    
    
    private CoyoteServletFacade() {
        proto = new Http11Protocol();

        mainAdapter = new MapperAdapter(mapper);        

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
    
    public Http11Protocol getProtocol() {
        return proto;
    }
    
    public void initHttp(int port) {
        proto.getEndpoint().setPort(port);
    }

    public void start() {
        if( proto.getEndpoint().getPort() == 0 ) { //&& 
                //proto.getEndpoint().getServerSocket() == null) {
            proto.getEndpoint().setPort(8800);
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
     * @throws ServletException 
     */
    public ServletContext createServletContext(String hostname, String path) 
            throws ServletException {
        
        Host host = (Host)hosts.get(hostname);
        if( host == null ) {
            host = new Host();
            host.setName(hostname);
            hosts.put(hostname, host);
            mapper.addHost(hostname, new String[] {}, host);
        }
        ServletContextImpl ctx = new ServletContextImpl();
        ctx.setParent(host);
        ctx.setPath(path);
        
        // TODO: read web.xml or equivalent
        
        // TODO: defaults 
        // 
        mapper.addContext(hostname, path, ctx, new String[] {"index.html"}, 
                null);
        
        host.addChild(ctx);
        
        // Add default mappings. 
        ServletConfig fileS = createServletWrapper(ctx, "file", 
                new FileServlet());
        addMapping("/", fileS);
        return ctx;
    }
    
    public void setBasePath(ServletContext ctx, String dir) {
        ((ServletContextImpl)ctx).setBasePath(dir);
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
        mapper.addWrapper(host.getName(), ctx.getPath(), path, wrapper);
        //new CoyoteServletAdapter(wrapper));
    }
    

    public void initContext(ServletContext ctx) {
        // Set up class loader.
        String base = ((ServletContextImpl)ctx).getBasePath();
        Repository ctxRepo = new Repository();
        ctxRepo.setParentClassLoader(this.getClass().getClassLoader());
        ctxRepo.addDir(new File(base + "/WEB-INF/classes"));
        ctxRepo.addLibs(new File(base + "/WEB-INF/lib"));
        
        ClassLoader cl = ctxRepo.getClassLoader();
        
        // Code-based configuration - experiment with generated web.xml->class
        try {
            Class c = cl.loadClass("WebappInit");
            WebappInitializer webInit = (WebappInitializer)c.newInstance();
            webInit.initWebapp(this, ctx);
        } catch(Throwable t) {
            t.printStackTrace();
        }
        
        // TODO: read a simpler version of web.xml
        
        // TODO: read the real web.xml
        
    }

}
