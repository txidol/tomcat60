/*
 */
package org.apache.tomcat.standalone;

import java.io.File;
import java.io.IOException;

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

/**
 * Minimal tomcat starter for embedding. 
 * 
 * Tomcat supports multiple styles of configuration. 
 * 
 * 1. Classical server.xml, web.xml - see the Tomcat superclass.
 * 2. Minimal tomcat, programatically configured, using regular webapps and web.xml
 * 3. For apps that only need basic servlets, or have their own deployment mechanism -
 *  you can start tomcat without using web.xml
 *  
 *  Before people start screaming 'violation of the spec' - please read again the
 *  spec, web.xml is a mechanism for _deployment_. If you ship or deploy a webapp, it
 *  must have web.xml and all the war format. However the spec doesn't require the
 *  container to store the files in the same format or use the web.xml file - they
 *  added a lot of pain to the spec to make sure files could be stored in a database
 *  for example. 
 *  
 *  In particular, storing the web.xml in a pre-parsed form, like a .ser file or 
 *  a generated java class is (IMO) perfectly fine. One particular case of this is 
 *  a hand-generated configurator - which is a good fit for apps that want to 
 *  provide a HTTP interface and use servlets, but don't need all dynamic deployment
 *  and reconfiguration.
 *  
 *  In fact - if you don't use jsps or use precompiled jsps, you can leave most of the 
 *  xml parsing overhead out of your app, and have a more minimal http server.
 * 
 * This is provided as a base class and as an example - you can extend it, or just 
 * cut&paste or reuse methods.
 * 
 * @author Costin Manolache
 */
public class ETomcat {
    
    protected StandardServer server;
    protected StandardService service;
    protected StandardEngine eng;
    protected StandardHost host;
    protected StandardContext ctx;

    /** Example main. You should extend ETomcat and call start() your own way.
     */
    public static void main( String args[] ) {
        try {
            ETomcat etomcat = new ETomcat();
            
            etomcat.initServer(null);
            etomcat.initConnector(8000);
            etomcat.initHost("localhost");
            etomcat.initWebapp("/", ".");
            etomcat.initWebappDefaults();
            
            etomcat.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public void start() throws Exception {
        server.initialize();
        server.start();
    }
    
    public StandardServer initServer(String baseDir) {
        initHome(baseDir);
        System.setProperty("catalina.useNaming", "false");
        
        server = new StandardServer();
        server.setPort( -1 );
        
        //ServerLifecycleListener jmxLoader = new ServerLifecycleListener();
        //server.addLifecycleListener(jmxLoader);
        
        service = new StandardService();
        server.addService( service );
        return server;
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
    
    /** Create a host. First host will be the default.
     */
    public StandardHost initHost(String hostname) {
        if(eng == null ) {
            eng = new StandardEngine();
            eng.setName( "default" );
            eng.setDefaultHost(hostname);
            service.setContainer(eng);
        }

        
        host = new StandardHost();
        host.setName(hostname);

        eng.addChild( host );
        return host;
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
    public StandardContext initWebapp(String path, String dir) {
        ctx = new StandardContext();
        ctx.setPath( path );
        ctx.setDocBase(dir);

        ctx.addLifecycleListener(new FixContextListener());

        host.addChild(ctx);
        return ctx;
    }
    
    public void addWebapp(StandardContext ctx) {
        this.ctx = ctx;
        
        ctx.addLifecycleListener(new FixContextListener());

        host.addChild(ctx);
        
    }

    public void initWebappDefaults() {
        // Default servlet 
        StandardWrapper defaultServletW = 
            initServlet("default", "org.apache.catalina.servlets.DefaultServlet");
        defaultServletW.addInitParameter("listings", "true");

        initServlet("invoker", "org.apache.catalina.servlets.InvokerServlet");

        initServlet("jsp", "org.apache.tomcat.servlets.jsp.JspProxyServlet");
        
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
    public StandardWrapper initServlet(String servletName, String servletClass) {
        // will do class for name and set init params
        StandardWrapper sw = (StandardWrapper)ctx.createWrapper();
        sw.setServletClass(servletClass);
        sw.setName(servletName);
        ctx.addChild(sw);
        
        return sw;
    }

    /** Use an existing servlet, no class.forName or initialization will be 
     * performed
     */
    public StandardWrapper initServlet(String servletName, Servlet servlet) {
        // will do class for name and set init params
        StandardWrapper sw = new ExistingStandardWrapper(servlet);
        sw.setName(servletName);
        ctx.addChild(sw);
        
        return sw;
    }

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
}
