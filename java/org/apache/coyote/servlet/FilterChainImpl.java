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


import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.apache.tomcat.util.res.StringManager;

// Not thread safe !!

/**
 * Implementation of <code>javax.servlet.FilterChain</code> used to manage
 * the execution of a set of filters for a particular request.  When the
 * set of defined filters has all been executed, the next call to
 * <code>doFilter()</code> will execute the servlet's <code>service()</code>
 * method itself.
 *
 * @author Craig R. McClanahan
 * @version $Revision: 303523 $ $Date: 2004-11-22 08:35:18 -0800 (Mon, 22 Nov 2004) $
 */

public final class FilterChainImpl implements FilterChain {

    public static final int INCREMENT = 8;

    public FilterChainImpl() {
        super();
    }

    /**
     * Filters.
     */
    private FilterConfigImpl[] filters = 
        new FilterConfigImpl[8];


    /**
     * The int which is used to maintain the current position 
     * in the filter chain.
     */
    private int pos = 0;


    /**
     * The int which gives the current number of filters in the chain.
     */
    private int n = 0;


    /**
     * The servlet instance to be executed by this chain.
     */
    private Servlet servlet = null;


    private ServletConfigImpl wrapper;


    /**
     * The string manager for our package.
     */
    private static final StringManager sm =
      StringManager.getManager("org.apache.coyote.servlet");


    // ---------------------------------------------------- FilterChain Methods


    /**
     * Invoke the next filter in this chain, passing the specified request
     * and response.  If there are no more filters in this chain, invoke
     * the <code>service()</code> method of the servlet itself.
     *
     * @param request The servlet request we are processing
     * @param response The servlet response we are creating
     *
     * @exception IOException if an input/output error occurs
     * @exception ServletException if a servlet exception occurs
     */
    public void doFilter(ServletRequest request, ServletResponse response)
        throws IOException, ServletException {


        // Call the next filter if there is one
        if (pos < n) {
            FilterConfigImpl filterConfig = filters[pos++];
            Filter filter = null;
            try {
                filter = filterConfig.getFilter();
                filter.doFilter(request, response, this);
            } catch (IOException e) {
                throw e;
            } catch (ServletException e) {
                throw e;
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                e.printStackTrace();
                throw new ServletException
                  (sm.getString("filterChain.filter"), e);
            }
            return;
        }

        // We fell off the end of the chain -- call the servlet instance
        try {
            if (servlet != null) 
                servlet.service(request, response);
        } catch (IOException e) {
            throw e;
        } catch (ServletException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new ServletException
              (sm.getString("filterChain.servlet"), e);
        }
    }


    // -------------------------------------------------------- Package Methods



    /**
     * Add a filter to the set of filters that will be executed in this chain.
     *
     * @param filterConfig The FilterConfig for the servlet to be executed
     */
    public void addFilter(FilterConfigImpl filterConfig) {
        if (n == filters.length) {
            FilterConfigImpl[] newFilters =
                new FilterConfigImpl[n + INCREMENT];
            System.arraycopy(filters, 0, newFilters, 0, n);
            filters = newFilters;
        }
        filters[n++] = filterConfig;
    }


    /**
     * Release references to the filters and wrapper executed by this chain.
     */
    void release() {
        n = 0;
        pos = 0;
        servlet = null;
    }


    /**
     * Set the servlet that will be executed at the end of this chain.
     * Set by the mapper filter 
     */
    public void setServlet(ServletConfigImpl wrapper, Servlet servlet) {
        this.wrapper = wrapper;
        this.servlet = servlet;
    }

    // ------ Getters for information ------------ 
    
    public int getSize() {
        return n;
    }
    
    public FilterConfigImpl getFilter(int i) {
        return filters[i];
    }
    
    public Servlet getServlet() {
        return servlet;
    }
    
    public ServletConfigImpl getServletConfig() {
        return wrapper;
    }
    
    public int getPos() {
        return pos;
    }
}
