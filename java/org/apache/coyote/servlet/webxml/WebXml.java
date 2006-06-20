/*
 */
package org.apache.coyote.servlet.webxml;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.apache.coyote.servlet.CoyoteServletFacade;
import org.apache.coyote.servlet.ServletConfigImpl;
import org.apache.coyote.servlet.ServletContextImpl;
import org.apache.tomcat.util.DomUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

public class WebXml {
    ServletContextImpl ctx;
    CoyoteServletFacade facade = CoyoteServletFacade.getServletImpl();
    
    public WebXml(ServletContext sctx) {
        ctx = (ServletContextImpl) sctx;
    }

    public void readWebXml(String baseDir) throws ServletException {
        try {
            File webXmlFile = new File( baseDir + "/WEB-INF/web.xml");
            if (!webXmlFile.exists()) {
                return;
            }
            FileInputStream fileInputStream = new FileInputStream(webXmlFile);
            Document document = 
                DomUtil.readXml(fileInputStream);
            
            Node webappNode = DomUtil.getChild(document, "web-app");
            
            Node confNode = DomUtil.getChild(webappNode, "filter");
            while (confNode != null ) {
                processFilter(confNode);
                confNode = DomUtil.getNext(confNode);
            }

            confNode = DomUtil.getChild(webappNode, "filter-mapping");
            while (confNode != null ) {
                processFilterMapping(confNode);
                confNode = DomUtil.getNext(confNode);
            }

            confNode = DomUtil.getChild(webappNode, "context-param");
            while (confNode != null ) {
                processContextParam(confNode);
                confNode = DomUtil.getNext(confNode);
            }

            confNode = DomUtil.getChild(webappNode, "mime-mapping");
            while (confNode != null ) {
                processFilterMapping(confNode);
                confNode = DomUtil.getNext(confNode);
            }

            confNode = DomUtil.getChild(webappNode, "error-page");
            while (confNode != null ) {
                processFilterMapping(confNode);
                confNode = DomUtil.getNext(confNode);
            }

            confNode = DomUtil.getChild(webappNode, "jsp-config");
            while (confNode != null ) {
                processFilterMapping(confNode);
                confNode = DomUtil.getNext(confNode);
            }

            confNode = DomUtil.getChild(webappNode, "servlet");
            while (confNode != null ) {
                processServlet(confNode);
                confNode = DomUtil.getNext(confNode);
            }

            confNode = DomUtil.getChild(webappNode, "servlet-mapping");
            while (confNode != null ) {
                processServletMapping(confNode);
                confNode = DomUtil.getNext(confNode);
            }

            confNode = DomUtil.getChild(webappNode, "listener");
            while (confNode != null ) {
                processListener(confNode);
                confNode = DomUtil.getNext(confNode);
            }

            confNode = DomUtil.getChild(webappNode, "security-constraint");
            while (confNode != null ) {
                processListener(confNode);
                confNode = DomUtil.getNext(confNode);
            }

            confNode = DomUtil.getChild(webappNode, "login-config");
            while (confNode != null ) {
                processListener(confNode);
                confNode = DomUtil.getNext(confNode);
                if (confNode != null) 
                    throw new ServletException("Multiple login-config");
            }

            confNode = DomUtil.getChild(webappNode, "session-config");
            while (confNode != null ) {
                processListener(confNode);
                confNode = DomUtil.getNext(confNode);
                if (confNode != null) 
                    throw new ServletException("Multiple session-config");
            }

            confNode = DomUtil.getChild(webappNode, "security-role");
            while (confNode != null ) {
                processListener(confNode);
                confNode = DomUtil.getNext(confNode);
            }


            confNode = DomUtil.getChild(webappNode, "env-entry");
            while (confNode != null ) {
                processListener(confNode);
                confNode = DomUtil.getNext(confNode);
            }

            // concatenate
            confNode = DomUtil.getChild(webappNode, "welcome-file-list");
            while (confNode != null ) {
                processListener(confNode);
                confNode = DomUtil.getNext(confNode);
            }

            // concatenate
            confNode = DomUtil.getChild(webappNode, "locale-encoding-mapping-list");
            while (confNode != null ) {
                processListener(confNode);
                confNode = DomUtil.getNext(confNode);
            }

            // TODO: warning about uniqueness of servlet name, filter name

        } catch (Exception e) {
            e.printStackTrace();
            throw new ServletException(e);
        }
    }
    
    private void processContextParam(Node confNode) {
        ctx.getContextParameters().put("", "");
    }

    
    /** Process anotations.
     */
    private void processMetadata() {
        
    }
    
    private void processListener(Node confNode) {
        String lClass = DomUtil.getChildContent(confNode, "listener-class");
        ctx.getListenersClassName().add(lClass);
    }

    private void processServlet(Node confNode) throws ServletException {
        String name = DomUtil.getChildContent(confNode,"servlet-name");
        String sclass = DomUtil.getChildContent(confNode,"servlet-class");
        
        HashMap initParams = new HashMap();
        processInitParams(confNode, initParams);
        
        ServletConfigImpl wrapper = (ServletConfigImpl) 
            facade.createServletWrapper(ctx, name, sclass, initParams);
        
        ctx.addServletConfig((ServletConfigImpl) wrapper);
    }

    private void processInitParams(Node confNode, HashMap initParams) {
        Node initN = DomUtil.getChild(confNode, "init-param");
        while (initN != null ) {
            String n = DomUtil.getChildContent(initN, "param-name");
            String v = DomUtil.getChildContent(initN, "param-value");
            initParams.put(n, v);
            confNode = DomUtil.getNext(initN);
        }
    }

    private void processServletMapping(Node confNode) {
        String name = DomUtil.getChildContent(confNode,"servlet-name");
        String path = DomUtil.getChildContent(confNode,"url-pattern");

        ServletConfigImpl wrapper = ctx.getServletConfig(name);
        facade.addMapping(path, wrapper);
    }

    private void processFilterMapping(Node confNode) {
        String name = DomUtil.getChildContent(confNode,"filter-name");
        // multiple 
        ArrayList dispatchers = new ArrayList();
        Node dataN = DomUtil.getChild(confNode, "dispatcher");
        while (dataN != null ) {
            String d = DomUtil.getContent(dataN);
            dispatchers.add(d);
            dataN = DomUtil.getNext(dataN);
        }
        String[] dispA = new String[ dispatchers.size() ];
        if (dispA.length > 0) {
            dispatchers.toArray(dispA);
        }
        
        dataN = DomUtil.getChild(confNode, "url-pattern");
        while (dataN != null ) {
            String path = DomUtil.getContent(dataN);
            dataN = DomUtil.getNext(dataN);
            ctx.getFilterMapper().addMapping(name, path, null, dispA);
        }
        dataN = DomUtil.getChild(confNode, "servlet-name");
        while (dataN != null ) {
            String sn = DomUtil.getContent(dataN);
            dataN = DomUtil.getNext(dataN);
            ctx.getFilterMapper().addMapping(name, null, sn, dispA);
        }
    }

    private void processFilter(Node confNode) {
        String name = DomUtil.getChildContent(confNode,"filter-name");
        String sclass = DomUtil.getChildContent(confNode,"filter-class");
        
        HashMap initParams = new HashMap();
        processInitParams(confNode, initParams);

        ctx.addFilter(name, sclass, initParams);
    }
    
}
