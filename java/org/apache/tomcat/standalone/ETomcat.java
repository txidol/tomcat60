/*
 */
package org.apache.tomcat.standalone;

import java.io.File;
import java.io.IOException;
import java.util.Hashtable;

import javax.servlet.Servlet;
import javax.servlet.ServletException;

import org.apache.catalina.Context;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.core.StandardService;
import org.apache.catalina.core.StandardWrapper;
import org.apache.catalina.deploy.LoginConfig;
import org.apache.catalina.session.StandardManager;
import org.apache.tomcat.util.IntrospectionUtils;

/**
 * Minimal tomcat starter for embedding. 
 * 
 * Tomcat supports multiple styles of configuration. 
 * 
 * 1. Classical server.xml-based. 
 *  Use org.apache.catalina.startup.Bootstrap as main class 
 * 2. No server.xml, deploy hosts using HostConfig
 *  Args: -hostbase BASE_HOST_PATH   
 * 3. No server.xml, load individual webapps using ContextConfig
 *  Args: -ctxbase BASE_WEBAPP_PATH  
 * 4. Single webapp, load individual servlets from current classpath ( no
 *   web.xml or other configuration ) for specific cases. This assumes 
 *   web.xml has been translated to method calls or args. Use method calls 
 *   to do this.
 *        
 * This is provided as a base class and as an example - you can extend it, or 
 * just cut&paste or reuse methods.
 * 
 * @author Costin Manolache
 */
public class ETomcat {
    
    protected StandardServer server;
    protected StandardService service;
    protected StandardEngine eng;

    public StandardEngine getEngine() {
        return eng;
    }
    
    /** First call - need to initialize tomcat objects.
     */
    public StandardServer initServer(String baseDir) {
        initHome(baseDir);
        System.setProperty("catalina.useNaming", "false");
        
        server = new StandardServer();
        server.setPort( -1 );
        
        service = new StandardService();
        server.addService( service );
        return server;
    }

    public void start() throws Exception {
        server.initialize();
        server.start();
    }

    /** Add a default http connector.
     * 
     * Alternatively, you can construct a Connector and set any params,
     * then call addConnector(Connector)
     */
    public Connector initConnector(int port) throws Exception {
        Connector connector = new Connector("HTTP/1.1");
        connector.setPort(port);
        service.addConnector( connector );
        return connector;
    }
    
    public void addConnector( Connector connector ) {
        service.addConnector( connector );
    }
    
    /** Initialize a webapp.
     * 
     * You can customize the return value to support different web.xml features:
     * 
     * context-param
     *  ctx.addParameter("name", "value");
     *     
     *
     * error-page
     *    ErrorPage ep = new ErrorPage();
     *    ep.setErrorCode(500);
     *    ep.setLocation("/error.html");
     *    ctx.addErrorPage(ep);
     *   
     * ctx.addMimeMapping("ext", "type");
     *  
     */
    public StandardContext initWebappSimple(StandardHost host, 
                                            String path, 
                                            String dir) {
        StandardContext ctx = new StandardContext();
        ctx.setPath( path );
        ctx.setDocBase(dir);

        ctx.addLifecycleListener(new FixContextListener());

        host.addChild(ctx);
        return ctx;
    }
    
    /** Init default servlets for the context. 
     */
    public void initWebappDefaults(StandardContext ctx) {
        // Default servlet 
        StandardWrapper defaultServletW = 
            initServlet(ctx, "default", 
                    "org.apache.catalina.servlets.DefaultServlet");
        defaultServletW.addInitParameter("listings", "true");

        initServlet(ctx, "invoker", "org.apache.catalina.servlets.InvokerServlet");

        initServlet(ctx, "jsp", "org.apache.tomcat.servlets.jsp.JspProxyServlet");
        
        ctx.addServletMapping("/", "default");
        ctx.addServletMapping("*.jsp", "jsp");
        ctx.addServletMapping("*.jspx", "jsp");
        ctx.addServletMapping("/servlet/*", "invoker");
        
        
        // Mime mappings: should read from /etc/mime.types on linux, or some
        // resource
        
        
        ctx.addWelcomeFile("index.html");
        ctx.addWelcomeFile("index.htm");
        ctx.addWelcomeFile("index.jsp");
        
        ctx.setLoginConfig( new LoginConfig("NONE", null, null, null));
        
        ctx.setManager( new StandardManager());
    }

    /**
     * You can customize the returned servlet, ex:
     * 
     *    wrapper.addInitParameter("name", "value");
     */
    public StandardWrapper initServlet(StandardContext ctx, 
                                       String servletName, 
                                       String servletClass) {
        // will do class for name and set init params
        StandardWrapper sw = (StandardWrapper)ctx.createWrapper();
        sw.setServletClass(servletClass);
        sw.setName(servletName);
        ctx.addChild(sw);
        
        return sw;
    }

    /** Use an existing servlet, no class.forName or initialization will be 
     *  performed
     */
    public StandardWrapper initServlet(StandardContext ctx,
                                       String servletName, 
                                       Servlet servlet) {
        // will do class for name and set init params
        StandardWrapper sw = new ExistingStandardWrapper(servlet);
        sw.setName(servletName);
        ctx.addChild(sw);
        
        return sw;
    }
    
    /** Create a host. First host will be the default.
     */
    public StandardHost initHostSimple(String hostname) {
        if(eng == null ) {
            eng = new StandardEngine();
            eng.setName( "default" );
            eng.setDefaultHost(hostname);
            service.setContainer(eng);
        }

        
        StandardHost host = new StandardHost();
        host.setName(hostname);

        eng.addChild( host );
        return host;
    }


    /** Init a host, using HostConfig. All webapps under webappsBase will be 
     * loaded
     */
    public StandardHost initHost(String hostName, 
                                 String webappsBase) 
        throws ServletException
    {
        StandardHost host = new StandardHost();
        host.setName(hostName);
        
        
        //HostConfig hconfig = new HostConfig();
        //host.addLifecycleListener( hconfig );
        try {
            Class c = Class.forName("org.apache.catalina.startup.HostConfig");
            LifecycleListener hconfig = (LifecycleListener) c.newInstance();
            host.addLifecycleListener(hconfig);
        } catch(Throwable t) {
            throw new ServletException(t);
        }

        host.setAppBase(webappsBase);

        getEngine().addChild(host);
        return host;
    }

    /** Init a webapp, using ContextConfig.
     */
    public StandardContext initWebapp(StandardHost host, 
                                      String url, String path) 
        throws ServletException
    {
        StandardContext ctx = new StandardContext();
        ctx.setPath( url );
        ctx.setDocBase(path);

        // web.xml reader
        try {
            Class c = Class.forName("org.apache.catalina.startup.ContextConfig");
            LifecycleListener hconfig = (LifecycleListener) c.newInstance();
            ctx.addLifecycleListener(hconfig);
        } catch(Throwable t) {
            throw new ServletException(t);
        }
        //  ContextConfig ctxCfg = new ContextConfig();
        //  ctx.addLifecycleListener( ctxCfg );
        
        host.addChild(ctx);
        return ctx;
    }

    // ---------- Helper methods and classes -------------------
    
    /** Init expected tomcat env. This is used as a base for the work directory.
     * TODO: disable work dir if not needed ( no jsp, etc ).
     * 
     * Also used as a base for webapps/ and config dirs, when used.
     */
    private void initHome(String basedir) {
        if( basedir != null ) {
            System.setProperty("catalina.home", basedir);
            System.setProperty("catalina.base", basedir);
        }
        String catalinaHome = System.getProperty("catalina.home");
        if(catalinaHome==null) {
            catalinaHome=System.getProperty("user.dir");
            File home = new File(catalinaHome);
            if (!home.isAbsolute()) {
                try {
                    catalinaHome = home.getCanonicalPath();
                } catch (IOException e) {
                    catalinaHome = home.getAbsolutePath();
                }
            }
            System.setProperty("catalina.home", catalinaHome);
        }
        
        if( System.getProperty("catalina.base") == null ) {
            System.setProperty("catalina.base", catalinaHome);
        }
    }
    
    /** Fix startup sequence - required if you don't use web.xml.
     * 
     *  The start() method in context will set 'configured' to false - and
     *  expects a listener to set it back to true.
     * 
     * @author Costin Manolache
     */
    public static class FixContextListener implements LifecycleListener {

        public void lifecycleEvent(LifecycleEvent event) {
            try {
                Context context = (Context) event.getLifecycle();
                if (event.getType().equals(Lifecycle.START_EVENT)) {
                    context.setConfigured(true);
                }
            } catch (ClassCastException e) {
                return;
            }
        }
        
    }

    /** Helper class for wrapping existing servlets. This disables servlet 
     * lifecycle and normal reloading, but also reduces overhead and provide
     * more direct control over the servlet.  
     *  
     * @author Costin Manolache
     */
    public static class ExistingStandardWrapper extends StandardWrapper {
        private Servlet existing;
        public ExistingStandardWrapper( Servlet existing ) {
            this.existing = existing;
        }
        public synchronized Servlet loadServlet() throws ServletException {
            return existing;

        }
        public long getAvailable() {
            return 0;
        }
        public boolean isUnavailable() {
            return false;       
        }
    }

    // ---------------- Command line processing -----------------------------

    
    StandardHost currentHost;
    StandardContext currentContext;
    
    public void setHost(String hostname) {
        currentHost = initHostSimple(hostname); 
    }
    
    
    /** Example main. You should extend ETomcat and call start() your own way.
     */
    public static void main( String args[] ) {
        try {
            ETomcat etomcat = new ETomcat();
            etomcat.initServer(null);
            
            IntrospectionUtils.processArgs(etomcat, args, 
                    new String[] {}, null, new Hashtable());

            etomcat.initConnector(8000);
            
            // 
            StandardHost host = etomcat.initHostSimple("localhost");
            StandardContext ctx = etomcat.initWebappSimple(host, "/", ".");
            // 
            etomcat.initWebappDefaults(ctx);
            
            etomcat.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    

}
