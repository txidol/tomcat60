/*
 * Copyright 1999,2004 The Apache Software Foundation.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.coyote.servlet;


import java.io.File;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.servlet.Filter;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextAttributeEvent;
import javax.servlet.ServletContextAttributeListener;
import javax.servlet.ServletException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.coyote.servlet.util.CharsetMapper;
import org.apache.coyote.servlet.util.Enumerator;
import org.apache.coyote.servlet.util.MappingData;
import org.apache.coyote.servlet.webxml.WebXml;
import org.apache.tomcat.servlets.file.DefaultServlet;
import org.apache.tomcat.util.buf.CharChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.loader.Repository;
import org.apache.tomcat.util.res.StringManager;


/**
 * Standard implementation of <code>ServletContext</code> that represents
 * a web application's execution environment.  An instance of this class is
 * associated with each instance of <code>StandardContext</code>.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 * @version $Revision: 377994 $ $Date: 2006-02-15 04:37:28 -0800 (Wed, 15 Feb 2006) $
 */

public class ServletContextImpl implements ServletContext {

    // ----------------------------------------------------------- Constructors

    public ServletContextImpl() {
    }

    // ----------------------------------------------------- Instance Variables
    /**
     * Empty collection to serve as the basis for empty enumerations.
     * <strong>DO NOT ADD ANY ELEMENTS TO THIS COLLECTION!</strong>
     */
    private static final ArrayList empty = new ArrayList();

    Log log;
    /**
     * The string manager for this package.
     */
    private static final StringManager sm =
      StringManager.getManager("org.apache.coyote.servlet");


    /**
     * The context attributes for this context.
     */
    private HashMap attributes = new HashMap();


    /**
     * List of read only attributes for this context.
     */
    private HashMap readOnlyAttributes = new HashMap();

    /** Internal mapper for request dispatcher, must have all 
     *  context mappings. 
     *  TODO: why do we need one mapper per context ? 
     */ 
    private WebappServletMapper mapper = new WebappServletMapper(this);

    private WebappHasRole realm = new WebappHasRole();

    /**
     * The merged context initialization parameters for this Context.
     */
    private HashMap parameters = new HashMap();

    private ArrayList lifecycleListenersClassName = new ArrayList();
    private ArrayList lifecycleListeners = new ArrayList();

    /**
     * Base path - the directory root of the webapp
     */
    private String basePath = null;

    /**
     * Thread local mapping data.
     */
    private ThreadLocal localMappingData = new ThreadLocal();

    /**
     * Thread local URI message bytes.
     */
    private ThreadLocal localUriMB = new ThreadLocal();

    private String contextName = "";
    
    private Host host;

    CharsetMapper charsetMapper = new CharsetMapper();

    String contextPath;

    private WebappSessionManager manager = new WebappSessionManager();

    Repository repository;

    HashMap filters = new HashMap();

    private WebappFilterMapper webappFilterMapper = new WebappFilterMapper(this);
    
    CoyoteServletFacade facade = CoyoteServletFacade.getServletImpl();

    // ------------------------------------------------- ServletContext Methods


    /**
     * Return the value of the specified context attribute, if any;
     * otherwise return <code>null</code>.
     *
     * @param name Name of the context attribute to return
     */
    public Object getAttribute(String name) {

        synchronized (attributes) {
            return (attributes.get(name));
        }

    }


    /**
     * Return an enumeration of the names of the context attributes
     * associated with this context.
     */
    public Enumeration getAttributeNames() {

        synchronized (attributes) {
            return new Enumerator(attributes.keySet(), true);
        }

    }


    /**
     * Return a <code>ServletContext</code> object that corresponds to a
     * specified URI on the server.  This method allows servlets to gain
     * access to the context for various parts of the server, and as needed
     * obtain <code>RequestDispatcher</code> objects or resources from the
     * context.  The given path must be absolute (beginning with a "/"),
     * and is interpreted based on our virtual host's document root.
     *
     * @param uri Absolute URI of a resource on the server
     */
    public ServletContext getContext(String uri) {

        // Validate the format of the specified argument
        if ((uri == null) || (!uri.startsWith("/")))
            return (null);

        ServletContextImpl child = null;
        try {
            Host host = (Host) this.getParent();
            String mapuri = uri;
            while (true) {
                child = (ServletContextImpl) host.findChild(mapuri);
                if (child != null)
                    break;
                int slash = mapuri.lastIndexOf('/');
                if (slash < 0)
                    break;
                mapuri = mapuri.substring(0, slash);
            }
        } catch (Throwable t) {
            return (null);
        }

        if (child == null)
            return (null);

        if (this.getCrossContext()) {
            // If crossContext is enabled, can always return the context
            return child.getServletContext();
        } else if (child == this) {
            // Can still return the current context
            return this.getServletContext();
        } else {
            // Nothing to return
            return (null);
        }
    }

    
    /**
     * Return the main path associated with this context.
     */
    public String getContextPath() {
        return contextPath;
    }
    

    /**
     * Return the value of the specified initialization parameter, or
     * <code>null</code> if this parameter does not exist.
     *
     * @param name Name of the initialization parameter to retrieve
     */
    public String getInitParameter(final String name) {
        return ((String) parameters.get(name));
    }


    /**
     * Return the names of the context's initialization parameters, or an
     * empty enumeration if the context has no initialization parameters.
     */
    public Enumeration getInitParameterNames() {
        return (new Enumerator(parameters.keySet()));
    }


    /**
     * Return the major version of the Java Servlet API that we implement.
     */
    public int getMajorVersion() {

        return 3;

    }


    /**
     * Return the minor version of the Java Servlet API that we implement.
     */
    public int getMinorVersion() {

        return 4;

    }


    /**
     * Return the MIME type of the specified file, or <code>null</code> if
     * the MIME type cannot be determined.
     *
     * @param file Filename for which to identify a MIME type
     */
    public String getMimeType(String file) {

        if (file == null)
            return (null);
        int period = file.lastIndexOf(".");
        if (period < 0)
            return (null);
        String extension = file.substring(period + 1);
        if (extension.length() < 1)
            return (null);
        return contentTypes.getProperty(extension);
    }


    /**
     * Return a <code>RequestDispatcher</code> object that acts as a
     * wrapper for the named servlet.
     *
     * @param name Name of the servlet for which a dispatcher is requested
     */
    public RequestDispatcher getNamedDispatcher(String name) {

        // Validate the name argument
        if (name == null)
            return (null);

        // Create and return a corresponding request dispatcher
        ServletConfigImpl wrapper = (ServletConfigImpl) this.getServletConfig(name);
        if (wrapper == null)
            return (null);
        
        return new RequestDispatcherImpl(wrapper, null, null, null, null, name);
    }


    /**
     * Return the real path for a given virtual path, if possible; otherwise
     * return <code>null</code>.
     *
     * @param path The path to the desired resource
     */
    public String getRealPath(String path) {
        if (path == null) {
            return null;
        }

        File file = new File(basePath, path);
        return (file.getAbsolutePath());
    }



    /**
     * Return a <code>RequestDispatcher</code> instance that acts as a
     * wrapper for the resource at the given path.  The path must begin
     * with a "/" and is interpreted as relative to the current context root.

     *
     * @param path The path to the desired resource.
     */
    public RequestDispatcher getRequestDispatcher(String path) {

        // Validate the path argument
        if (path == null)
            return (null);
        if (!path.startsWith("/"))
            throw new IllegalArgumentException
                (sm.getString
                 ("applicationContext.requestDispatcher.iae", path));
        path = normalize(path);
        if (path == null)
            return (null);

        // Retrieve the thread local URI
        MessageBytes uriMB = (MessageBytes) localUriMB.get();
        if (uriMB == null) {
            uriMB = MessageBytes.newInstance();
            CharChunk uriCC = uriMB.getCharChunk();
            uriCC.setLimit(-1);
            localUriMB.set(uriMB);
        } else {
            uriMB.recycle();
        }

        // Get query string
        String queryString = null;
        int pos = path.indexOf('?');
        if (pos >= 0) {
            queryString = path.substring(pos + 1);
        } else {
            pos = path.length();
        }
 
        // Retrieve the thread local mapping data
        MappingData mappingData = (MappingData) localMappingData.get();
        if (mappingData == null) {
            mappingData = new MappingData();
            localMappingData.set(mappingData);
        }

        // Map the URI
        CharChunk uriCC = uriMB.getCharChunk();
        try {
            uriCC.append(path, 0, path.length());
            /*
             * Ignore any trailing path params (separated by ';') for mapping
             * purposes
             */
            int semicolon = path.indexOf(';');
            if (pos >= 0 && semicolon > pos) {
                semicolon = -1;
            }
            uriCC.append(path, 0, semicolon > 0 ? semicolon : pos);
            this.getMapper().map(uriMB, mappingData);
            if (mappingData.wrapper == null) {
                return (null);
            }
            /*
             * Append any trailing path params (separated by ';') that were
             * ignored for mapping purposes, so that they're reflected in the
             * RequestDispatcher's requestURI
             */
            if (semicolon > 0) {
                uriCC.append(path, semicolon, pos - semicolon);
            }
        } catch (Exception e) {
            // Should never happen
            log(sm.getString("applicationContext.mapping.error"), e);
            return (null);
        }

        ServletConfigImpl wrapper = (ServletConfigImpl) mappingData.wrapper;
        String wrapperPath = mappingData.wrapperPath.toString();
        String pathInfo = mappingData.pathInfo.toString();

        mappingData.recycle();
        
        // Construct a RequestDispatcher to process this request
        return new RequestDispatcherImpl
            (wrapper, uriCC.toString(), wrapperPath, pathInfo, 
             queryString, null);

    }



    /**
     * Return the URL to the resource that is mapped to a specified path.
     * The path must begin with a "/" and is interpreted as relative to the
     * current context root.
     *
     * @param path The path to the desired resource
     *
     * @exception MalformedURLException if the path is not given
     *  in the correct form
     */
    public URL getResource(String path)
        throws MalformedURLException {

        if (path == null || !path.startsWith("/")) {
            throw new MalformedURLException(sm.getString("applicationContext.requestDispatcher.iae", path));
        }
        
        path = normalize(path);
        if (path == null)
            return (null);

        String libPath = "/WEB-INF/lib/";
        if ((path.startsWith(libPath)) && (path.endsWith(".jar"))) {
            File jarFile = null;
            jarFile = new File(basePath, path);
            if (jarFile.exists()) {
                return jarFile.toURL();
            } else {
                return null;
            }
        } else {
        }

        return (null);

    }


    Host getParent() {
        return host;
    }

    void setParent(Host host) {
        this.host = host;
    }

    String getName() {
        return null;
    }


    private String getWorkPath() {
        return null;
    }


    /**
     * Return the requested resource as an <code>InputStream</code>.  The
     * path must be specified according to the rules described under
     * <code>getResource</code>.  If no such resource can be identified,
     * return <code>null</code>.
     *
     * @param path The path to the desired resource.
     */
    public InputStream getResourceAsStream(String path) {

        path = normalize(path);
        if (path == null)
            return (null);

        // TODO(costin): file based resources
//        DirContext resources = this.getResources();
//        if (resources != null) {
//            try {
//                Object resource = resources.lookup(path);
//                if (resource instanceof Resource)
//                    return (((Resource) resource).streamContent());
//            } catch (Exception e) {
//            }
//        }
        return (null);

    }


    /**
     * Return a Set containing the resource paths of resources member of the
     * specified collection. Each path will be a String starting with
     * a "/" character. The returned set is immutable.
     *
     * @param path Collection path
     */
    public Set getResourcePaths(String path) {

        // Validate the path argument
        if (path == null) {
            return null;
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException
                (sm.getString("applicationContext.resourcePaths.iae", path));
        }

        path = normalize(path);
        if (path == null)
            return (null);

        File f = new File(basePath + path);
        File[] files = f.listFiles();
        if (files == null) return null;
        
        HashSet result = new HashSet();
        for (int i=0; i < files.length; i++) {
            if (files[i].isDirectory() ) {
                result.add(files[i].getName() + "/");
            } else {
                result.add(files[i].getName());
            }
        }
        return result;
    }



    /**
     * Return the name and version of the servlet container.
     */
    public String getServerInfo() {

        return "Apache Tomcat";

    }


    /**
     * @deprecated As of Java Servlet API 2.1, with no direct replacement.
     */
    public Servlet getServlet(String name) {

        return (null);

    }


    /**
     * Return the display name of this web application.
     */
    public String getServletContextName() {

        return contextName ;

    }


    /**
     * @deprecated As of Java Servlet API 2.1, with no direct replacement.
     */
    public Enumeration getServletNames() {
        return (new Enumerator(empty));
    }


    /**
     * @deprecated As of Java Servlet API 2.1, with no direct replacement.
     */
    public Enumeration getServlets() {
        return (new Enumerator(empty));
    }


    /**
     * Writes the specified message to a servlet log file.
     *
     * @param message Message to be written
     */
    public void log(String message) {

        this.getLogger().info(message);

    }


    /**
     * Writes the specified exception and message to a servlet log file.
     *
     * @param exception Exception to be reported
     * @param message Message to be written
     *
     * @deprecated As of Java Servlet API 2.1, use
     *  <code>log(String, Throwable)</code> instead
     */
    public void log(Exception exception, String message) {
        
        this.getLogger().error(message, exception);

    }


    /**
     * Writes the specified message and exception to a servlet log file.
     *
     * @param message Message to be written
     * @param throwable Exception to be reported
     */
    public void log(String message, Throwable throwable) {
        
        this.getLogger().error(message, throwable);

    }


    /**
     * Remove the context attribute with the specified name, if any.
     *
     * @param name Name of the context attribute to be removed
     */
    public void removeAttribute(String name) {

        Object value = null;
        boolean found = false;

        // Remove the specified attribute
        synchronized (attributes) {
            // Check for read only attribute
           if (readOnlyAttributes.containsKey(name))
                return;
            found = attributes.containsKey(name);
            if (found) {
                value = attributes.get(name);
                attributes.remove(name);
            } else {
                return;
            }
        }

        // Notify interested application event listeners
        List listeners = this.getApplicationEventListeners();
        if (listeners.size() == 0)
            return;
        ServletContextAttributeEvent event = null;
        for (int i = 0; i < listeners.size(); i++) {
            if (!(listeners.get(i) instanceof ServletContextAttributeListener))
                continue;
            ServletContextAttributeListener listener =
                (ServletContextAttributeListener) listeners.get(i);
            try {
//                this.fireContainerEvent("beforeContextAttributeRemoved",
//                                           listener);
                if (event == null) {
                    event = new ServletContextAttributeEvent(this.getServletContext(),
                            name, value);

                }
                listener.attributeRemoved(event);
//                this.fireContainerEvent("afterContextAttributeRemoved",
//                                           listener);
            } catch (Throwable t) {
//                this.fireContainerEvent("afterContextAttributeRemoved",
//                                           listener);
                // FIXME - should we do anything besides log these?
                log(sm.getString("applicationContext.attributeEvent"), t);
            }
        }

    }


    /**
     * Bind the specified value with the specified context attribute name,
     * replacing any existing value for that name.
     *
     * @param name Attribute name to be bound
     * @param value New attribute value to be bound
     */
    public void setAttribute(String name, Object value) {

        // Name cannot be null
        if (name == null)
            throw new IllegalArgumentException
                (sm.getString("applicationContext.setAttribute.namenull"));

        // Null value is the same as removeAttribute()
        if (value == null) {
            removeAttribute(name);
            return;
        }

        Object oldValue = null;
        boolean replaced = false;

        // Add or replace the specified attribute
        synchronized (attributes) {
            // Check for read only attribute
            if (readOnlyAttributes.containsKey(name))
                return;
            oldValue = attributes.get(name);
            if (oldValue != null)
                replaced = true;
            attributes.put(name, value);
        }

        // Notify interested application event listeners
        List listeners = this.getApplicationEventListeners();
        if (listeners.size() == 0)
            return;
        ServletContextAttributeEvent event = null;
        for (int i = 0; i < listeners.size(); i++) {
            if (!(listeners.get(i) instanceof ServletContextAttributeListener))
                continue;
            ServletContextAttributeListener listener =
                (ServletContextAttributeListener) listeners.get(i);
            try {
                if (event == null) {
                    if (replaced)
                        event =
                            new ServletContextAttributeEvent(this.getServletContext(),
                                                             name, oldValue);
                    else
                        event =
                            new ServletContextAttributeEvent(this.getServletContext(),
                                                             name, value);
                    
                }
                if (replaced) {
//                    this.fireContainerEvent
//                        ("beforeContextAttributeReplaced", listener);
                    listener.attributeReplaced(event);
//                    this.fireContainerEvent("afterContextAttributeReplaced",
//                                               listener);
                } else {
//                    this.fireContainerEvent("beforeContextAttributeAdded",
//                                               listener);
                    listener.attributeAdded(event);
//                    this.fireContainerEvent("afterContextAttributeAdded",
//                                               listener);
                }
            } catch (Throwable t) {
//                if (replaced)
//                    this.fireContainerEvent("afterContextAttributeReplaced",
//                                               listener);
//                else
//                    this.fireContainerEvent("afterContextAttributeAdded",
//                                               listener);
                // FIXME - should we do anything besides log these?
                log(sm.getString("applicationContext.attributeEvent"), t);
            }
        }

    }


    // -------------------------------------------------------- Package Methods


    /**
     * Clear all application-created attributes.
     */
    void clearAttributes() {

        // Create list of attributes to be removed
        ArrayList list = new ArrayList();
        synchronized (attributes) {
            Iterator iter = attributes.keySet().iterator();
            while (iter.hasNext()) {
                list.add(iter.next());
            }
        }

        // Remove application originated attributes
        // (read only attributes will be left in place)
        Iterator keys = list.iterator();
        while (keys.hasNext()) {
            String key = (String) keys.next();
            removeAttribute(key);
        }
        
    }
    
    
//    /**
//     * Return the facade associated with this ApplicationContext.
//     */
//    protected ServletContext getFacade() {
//
//        return (this.facade);
//
//    }


    /**
     * Set an attribute as read only.
     */
    void setAttributeReadOnly(String name) {

        synchronized (attributes) {
            if (attributes.containsKey(name))
                readOnlyAttributes.put(name, name);
        }

    }


    // -------------------------------------------------------- Private Methods


    /**
     * Return a context-relative path, beginning with a "/", that represents
     * the canonical version of the specified path after ".." and "." elements
     * are resolved out.  If the specified path attempts to go outside the
     * boundaries of the current context (i.e. too many ".." path elements
     * are present), return <code>null</code> instead.
     *
     * @param path Path to be normalized
     */
    private String normalize(String path) {

        if (path == null) {
            return null;
        }

        String normalized = path;

        // Normalize the slashes and add leading slash if necessary
        if (normalized.indexOf('\\') >= 0)
            normalized = normalized.replace('\\', '/');

        // Resolve occurrences of "/../" in the normalized path
        while (true) {
            int index = normalized.indexOf("/../");
            if (index < 0)
                break;
            if (index == 0)
                return (null);  // Trying to go outside our context
            int index2 = normalized.lastIndexOf('/', index - 1);
            normalized = normalized.substring(0, index2) +
                normalized.substring(index + 3);
        }

        // Return the normalized path that we have completed
        return (normalized);

    }

    public CharsetMapper getCharsetMapper() {
        return charsetMapper;
    }

    void setContextPath(String path) {
        this.contextPath = path;
        mapper.contextMapElement.name = path;
        log = LogFactory.getLog("webapp." + path.replace("/", "."));
    }
    
    void setBasePath(String basePath) {
        this.basePath = basePath;        
    }

    public String getBasePath() {
        return basePath;
    }

    public WebappSessionManager getManager() {
        return manager;
    }


    public WebappHasRole getRealm() {
        return realm;
    }


    public String getEncodedPath() {
        return null;
    }


    public boolean getCookies() {
        return false;
    }


    public ServletContext getServletContext() {
        return this;
    }


    public List getApplicationEventListeners() {
        return lifecycleListeners;
    }

    public List getListenersClassName() {
        return lifecycleListenersClassName;
    }

    public Log getLogger() {
        return log;
    }


    public boolean getSwallowOutput() {
        return false;
    }


    public long getUnloadDelay() {
        return 0;
    }

    HashMap servlets = new HashMap();

    public ServletConfigImpl getServletConfig(String jsp_servlet_name) {
        return (ServletConfigImpl)servlets.get(jsp_servlet_name);
    }

    public void addServletConfig(ServletConfigImpl servletConfig) {
        servlets.put(servletConfig.getServletName(), servletConfig);
        
    }

    public boolean getPrivileged() {
        return false;
    }

    private boolean getCrossContext() {
        return true;
    }

    static Properties contentTypes=new Properties();
    static {
        initContentTypes();
    }
    // TODO: proper implementation
    static void initContentTypes() {
        contentTypes.put("xhtml", "text/html");
        contentTypes.put("html", "text/html");
        contentTypes.put("txt", "text/plain");
        contentTypes.put("css", "text/css");
        contentTypes.put("xul", "application/vnd.mozilla.xul+xml");
    }
    
    public void addMimeType(String ext, String type) {
        contentTypes.put(ext, type);
    }

    public WebappServletMapper getMapper() {
        return mapper;
    }
    
    public WebappFilterMapper getFilterMapper() {
        return webappFilterMapper ;
    }
    
    public FilterConfigImpl getFilter(String name) {
        return (FilterConfigImpl)filters.get(name);
    }

    public void addFilter(String name, String className, Map initParams) {
        FilterConfigImpl fc = new FilterConfigImpl(this);
        fc.setFilterClass(className);
        fc.setFilterName(name);
        fc.setParameterMap(initParams);
        filters.put(name, fc);

        // Filters are added before mappings - if the WebappFilterMapper
        // is replaced, it can get all the mappings.
        try {
            if (name.equals("_tomcat.FilterMapper")) {
                Filter filter = fc.getFilter();
                if (filter instanceof WebappFilterMapper) {
                    webappFilterMapper = (WebappFilterMapper)filter;
                }
            }
            if (name.equals("_tomcat.ServletMapper")) {
                Filter filter = fc.getFilter();
                if (filter instanceof WebappServletMapper) {
                    mapper = (WebappServletMapper)filter;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public void initFilters() throws ServletException {
        Iterator fI = getFilters().values().iterator();
        while (fI.hasNext()) {
            FilterConfigImpl fc = (FilterConfigImpl)fI.next();
            try {
                fc.getFilter(); // will triger init()
            } catch (Throwable e) {
                log.warn("Error initializing filter " + fc.getFilterName(), e);
            } 
            
        }
    }
    
    public void initListeners() throws ServletException {
        Iterator fI = getListenersClassName().iterator();
        while (fI.hasNext()) {
            String listenerClass = (String)fI.next();
            try {
                Object l = 
                    getClassLoader().loadClass(listenerClass).newInstance();
                lifecycleListeners.add(l);
            } catch (Throwable e) {
                log.warn("Error initializing listener " + listenerClass, e);
            } 
            
        }
    }
    
    public Map getFilters() {
        return filters;
    }
    

    public void setRepository(Repository repo) {
        repository = repo;
    }
    
    public ClassLoader getClassLoader() {
        if( repository != null ) 
            return repository.getClassLoader();
        return this.getClass().getClassLoader();
    }

    public Map getContextParameters() {
        return parameters;
    }


    public void init() throws ServletException {
        String base = getBasePath();
        
        // create a class loader
        Repository ctxRepo = new Repository();
        ctxRepo.setParentClassLoader(this.getClass().getClassLoader());
        ctxRepo.addDir(new File(base + "/WEB-INF/classes"));
        ctxRepo.addLibs(new File(base + "/WEB-INF/lib"));
        setRepository(ctxRepo);
        ClassLoader cl = ctxRepo.getClassLoader();

        // Add default mappings.
        ServletConfig fileS =
            facade.createServletWrapper(this, "default", new DefaultServlet());
        facade.addMapping("/", fileS);

        WebXml webXml = new WebXml(this);
        webXml.readWebXml(base);

        // TODO: read a simpler version of web.xml

        // TODO: read the real web.xml

        facade.notifyAdd(this);
        
        initFilters();
        initListeners();
    }
    
    public void destroy() throws ServletException {
        // destroy filters
        Iterator fI = filters.values().iterator();
        while(fI.hasNext()) {
            FilterConfigImpl fc = (FilterConfigImpl) fI.next();
            try {
                fc.getFilter().destroy();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        // destroy servlets
        fI = servlets.values().iterator();
        while(fI.hasNext()) {
            ServletConfigImpl fc = (ServletConfigImpl) fI.next();
            try {
                fc.destroy();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

}

