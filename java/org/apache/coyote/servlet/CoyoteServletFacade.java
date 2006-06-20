/*
 */
package org.apache.coyote.servlet;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.apache.coyote.servlet.webxml.WebXml;
import org.apache.tomcat.servlets.file.DefaultServlet;
import org.apache.tomcat.util.loader.Repository;
import org.apache.tomcat.util.net.http11.Http11Protocol;

/**
 * Frontend for a minimal servlet impl for coyote.
 * 
 * This is based on catalina classes, with non-essential features removed. It is
 * possible to add back some of the flexibility using callbacks/hooks - but this
 * package should be a sufficient implementation for a working servlet
 * container, with no extra features ( just hooks ).
 * 
 * Most of the implementation classes are not public - and must be accessed via
 * the facade. This can provide additional control and may simplify the
 * interface ( while allowing changes in the impl ).
 * 
 * @author Costin Manolache
 */
public class CoyoteServletFacade {
    private static CoyoteServletFacade facade = new CoyoteServletFacade();

    /**
     * Simple interface to be used by manually or generated web.xml readers.
     */
    public static interface WebappInitializer {
        public void initWebapp(CoyoteServletFacade facade, ServletContext ctx)
            throws ServletException;
    }

    protected ManagedObjectListener[] listeners = new ManagedObjectListener[8];

    protected Http11Protocol proto;

    /*
     * Top-level mapper.
     */
    protected CoyoteAdapter mainAdapter;

    String hostname = ""; // current hostname, used for settings

    private CoyoteServletFacade() {
        proto = new Http11Protocol();
        mainAdapter = new CoyoteAdapter();
        proto.setAdapter(mainAdapter);
    }

    public static CoyoteServletFacade getServletImpl() {
        // TODO: restrict access to only first call.
        return facade;
    }

    // --------------- Connector related features -------------

    /**
     * Fine tunning
     */
    public Http11Protocol getProtocol() {
        return proto;
    }

    /** 
     */
    public void setPort(int port) {
        proto.getEndpoint().setPort(port);
    }

    // --------------- start/stop ---------------

    public void start() {
        // start all contexts

        try {
            proto.init();
            
            // init all contexts
            Iterator hostI = mainAdapter.getHostMapper().getHosts();
            while(hostI.hasNext()) {
                Host host = (Host)hostI.next();
                Iterator ctxI = host.getContexts();
                while (ctxI.hasNext()) {
                    ServletContextImpl ctx = (ServletContextImpl)ctxI.next();
                    ctx.init();
                }
            }
            
            proto.start();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        try {
            proto.destroy();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        // TODO: stop all contexts
    }

    // --------------- Host add/remove -------------

    public Host getDefaultHost() {
        return mainAdapter.hostMapper.defaultHost;
    }

    /**
     * Add a host. You don't need to add the default host.
     * 
     * Hosts must be added before any context for that host. If a context is
     * added without a host, the default host will be used.
     * 
     */
    public Host addHost(String name, String aliases[]) {
        Host host = new Host();
        host.setName(name);

        mainAdapter.getHostMapper().addHost(name, aliases, host);
        notifyAdd(host);
        return host;
    }

    // -------------- Context add/remove --------------

    /**
     * 
     * @param hostname - ""
     *            if default host, or string to be matched with Host header
     * @param path -
     *            context path, "/" for root, "/examples", etc
     * @return a servlet context
     * @throws ServletException
     */
    public ServletContext addServletContext(String hostname, String basePath,
                                            String path)
        throws ServletException
    {

        Host host =
            (hostname == null) ? getDefaultHost() : mainAdapter.getHostMapper()
                    .findHost(hostname);
            
        ServletContextImpl ctx = new ServletContextImpl();
        ctx.setParent(host);
        ctx.setContextPath(path);
        ctx.setBasePath(basePath);

        // TODO: read web.xml or equivalent

        // TODO: defaults
        // 
        mainAdapter.getHostMapper().addContext(hostname, ctx,
                                               new String[] { "index.html" },
                                               new File(basePath));

        host.addChild(ctx);

        return ctx;
    }

    public void removeServletContext(ServletContext sctx)
        throws ServletException
    {
        ServletContextImpl ctx = (ServletContextImpl) sctx;

        
        // TODO: destroy all servlets and filters
        // TODO: clean up any other reference to the context or its loader
        
        mainAdapter.getHostMapper().removeContext(ctx.getParent().getName(),
                                                  ctx.getContextPath());
        notifyRemove(ctx);
    }

    public ServletContext reloadServletContext(ServletContext sctx)
        throws ServletException
    {
        ServletContextImpl ctx = (ServletContextImpl) sctx;
        String hostname = ctx.getParent().getName();
        String basePath = ctx.getBasePath();
        String path = ctx.getContextPath();

        removeServletContext(sctx);

        ServletContextImpl res = 
            (ServletContextImpl)addServletContext(hostname, basePath, path);
        res.init();
        return res;
    }
    

    // -------------- Web.xml reader will call this ---------
    // For specialized cases - you can call this directly
    // Experiment: WebXmlConfig to call this or generate properties, then
    // at run time read the properties.

    public ServletConfig createServletWrapper(ServletContext ctx, String name,
                                              Servlet servlet)
        throws ServletException
    {
        ServletConfigImpl w = new ServletConfigImpl();
        // TODO: postpone to first use
        // TODO: grab config
        w.setServlet(servlet);
        w.setParent((ServletContextImpl) ctx);

        ((ServletContextImpl)ctx).addServletConfig(w);
        notifyAdd(w);
        // won't be called automatically if Servlet is used.
        servlet.init(w);
        return w;
    }

    public ServletConfig createServletWrapper(ServletContext ctx, String name,
                                              String servletClass, Map initParams)
        throws ServletException
    {
        ServletConfigImpl w = new ServletConfigImpl();
        // TODO: postpone to first use
        // TODO: grab config
        w.setServletClass(servletClass);
        w.setParent((ServletContextImpl) ctx);
        w.setServletName(name);
        
        if (initParams != null)
            w.setInitParameters(initParams);
        
        ((ServletContextImpl)ctx).addServletConfig(w);
        notifyAdd(w);
        return w;
    }

    public void addMapping(String path, ServletConfig wrapper) {
        ServletContextImpl ctx =
            (ServletContextImpl) wrapper.getServletContext();
        ctx.getMapper().addWrapper(ctx.getMapper().contextMapElement, path, wrapper);
    }

    // ------------ Notifications for JMX ----------------

    /**
     * Hook for JMX
     */
    public static interface ManagedObjectListener {
        public void addManagedObject(Object o);

        public void removeManagedObject(Object o);
    }

    public void addManagedObjectListener(ManagedObjectListener l) {
        for (int i = 0; i < listeners.length; i++) {
            if (listeners[i] == null) {
                listeners[i] = l;
                return;
            }
        }
        // TODO: resize
    }

    public void removeManagedObjectListener(ManagedObjectListener l) {
        int found = -1;
        int i = 0;
        while (i < listeners.length) {
            if (listeners[i] == l) {
                found = i;
            }
            if (listeners[i] == null) {
                i--;
                break;
            }
            i++;
        }
        if (found == -1)
            return;
        if (found == i) {
            listeners[found] = null;
        }
        listeners[found] = listeners[i];
        listeners[i] = null;
    }

    void notifyAdd(Object o) {
        for (int i = 0; i < listeners.length; i++) {
            if (listeners[i] == null)
                return;
            try {
                listeners[i].addManagedObject(o);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    void notifyRemove(Object o) {
        for (int i = 0; i < listeners.length; i++) {
            if (listeners[i] == null)
                return;
            try {
                listeners[i].removeManagedObject(o);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }
}
