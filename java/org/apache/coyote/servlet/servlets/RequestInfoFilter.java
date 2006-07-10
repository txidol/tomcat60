/*
 */
package org.apache.coyote.servlet.servlets;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Enumeration;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestWrapper;
import javax.servlet.ServletResponse;
import javax.servlet.ServletResponseWrapper;

import org.apache.coyote.servlet.FilterChainImpl;
import org.apache.coyote.servlet.ServletRequestImpl;
import org.apache.coyote.servlet.ServletResponseImpl;
import org.apache.tomcat.util.http.MimeHeaders;

public class RequestInfoFilter implements Filter {

    public void init(FilterConfig filterConfig) throws ServletException {
    }

    public void doFilter(ServletRequest request, 
                         ServletResponse response, 
                         FilterChain chain) 
            throws IOException, ServletException {
        
        PrintStream out = System.err;
        while (request instanceof ServletRequestWrapper) {
            request = ((ServletRequestWrapper)request).getRequest();
        }
        ServletRequestImpl req = (ServletRequestImpl)request;
        // TODO: cookies, session, headers
        out.println("Q(" + 
                req.getMethod() + " H:" + 
                req.getServerName() + " L:" +
                req.getContentLength() + " C:" +
                req.getContextPath() + " S:" + 
                req.getServletPath() + " P:" +
                req.getPathInfo() + " Q:" +
                req.getQueryString() + " " +
                ")");
        FilterChainImpl filterChain = req.getFilterChain();
        for( int i=0; i<filterChain.getSize(); i++) {
            out.println("F: " +filterChain.getFilter(i).getFilterName());
        }
        out.println("S: " + filterChain.getServletConfig().getServletName());
        
        Enumeration headerNames = req.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String hn = (String)headerNames.nextElement();
            Enumeration headers = req.getHeaders(hn);
            while(headers.hasMoreElements()) {
                out.println(hn + ": " + headers.nextElement());
            }
        }
        chain.doFilter(request, response);
        while (response instanceof ServletResponseWrapper) {
            response = ((ServletResponseWrapper)response).getResponse();
        }
        if ( response instanceof ServletResponseImpl) {
            ServletResponseImpl res = (ServletResponseImpl)response;
            out.println("R(" + res.getStatus() + " " +
                    res.getContentLength() + " " + 
                    res.getContentType() + " " + 
                    ")");
            MimeHeaders headerNames2 = res.getCoyoteResponse().getMimeHeaders();
            for( int i = 0; i < headerNames2.size(); i++ ) {
                out.println(headerNames2.getName(i) + ": " + 
                        headerNames2.getValue(i));
            }
        } else {
            out.println("R(Unexpected wrapped object)");
        }
        out.println();
    }

    public void destroy() {
    }

}
