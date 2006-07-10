/*
 * Copyright 1999-2001,2004 The Apache Software Foundation.
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


package org.apache.tomcat.servlets.sec;


import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;


/**
 * Simpler IP filter - no regexp, patterns, etc.
 * 
 * Allows only localhost or a specified address
 * 
 * @author Costin Manolache
 */
public class SimpleIPFilter implements Filter {
   
    
    // --------------------------------------------------------- Public Methods
    public SimpleIPFilter() {
        
    }
    
    public void setAddress(String addr) {
    }
        
    public void destroy() {
    }


    public void doFilter(ServletRequest request, 
                         ServletResponse servletResponse, 
                         FilterChain chain) 
            throws IOException, ServletException {
        
        HttpServletResponse response = (HttpServletResponse)servletResponse;
        String property = request.getRemoteAddr();
        
        // Allow if denies specified but not allows
        if ("127.0.0.1".equals(property)) {
            chain.doFilter(request, response);
            return;
        }

        // Deny this request
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }


    public void init(FilterConfig filterConfig) throws ServletException {
    }


}
