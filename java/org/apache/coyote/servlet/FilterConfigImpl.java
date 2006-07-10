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


import java.io.Serializable;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.apache.coyote.servlet.util.Enumerator;
import org.apache.tomcat.util.log.SystemLogHandler;


/** 
 * A Filter is configured in web.xml by:
 *  - name - used in mappings
 *  - className - used to instantiate the filter
 *  - init params
 *  - other things not used in the servlet container ( icon, descr, etc )
 *  
 *  
 */
public final class FilterConfigImpl implements FilterConfig, Serializable {

    public FilterConfigImpl(ServletContextImpl context) {
        super();
        this.context = context;
    }

    /**
     * The Context with which we are associated.
     */
    private ServletContextImpl context = null;

    private Map parameters = new HashMap();

    /**
     * The application Filter we are configured for.
     */
    private transient Filter filter = null;

    private String filterName = null;

    private String filterClass = null;

    void setFilterName(String filterName) {
        this.filterName = filterName;
    }
    
    public Map getParameterMap() {
        return (this.parameters);
    }

    public void setParameterMap(Map initParams) {
        parameters = initParams;
    }

    public String getFilterClass() {
        return (this.filterClass);
    }

    public void setFilterClass(String filterClass) {
        this.filterClass = filterClass;
    }

    // --------------------------------------------------- FilterConfig Methods

    public String getFilterName() {
        return (this.filterName);
    }

    public String getInitParameter(String name) {
        Map map = getParameterMap();
        if (map == null)
            return (null);
        else
            return ((String) map.get(name));
    }


    /**
     * Return an <code>Enumeration</code> of the names of the initialization
     * parameters for this Filter.
     */
    public Enumeration getInitParameterNames() {

        Map map = getParameterMap();
        if (map == null)
            return (new Enumerator(new ArrayList()));
        else
            return (new Enumerator(map.keySet()));

    }


    /**
     * Return the ServletContext of our associated web application.
     */
    public ServletContext getServletContext() {
        return context;
    }

    /**
     * Return the application Filter we are configured for.
     *
     * @exception ClassCastException if the specified class does not implement
     *  the <code>javax.servlet.Filter</code> interface
     * @exception ClassNotFoundException if the filter class cannot be found
     * @exception IllegalAccessException if the filter class cannot be
     *  publicly instantiated
     * @exception InstantiationException if an exception occurs while
     *  instantiating the filter object
     * @exception ServletException if thrown by the filter's init() method
     */
    Filter getFilter() throws ClassCastException, ClassNotFoundException,
        IllegalAccessException, InstantiationException, ServletException {

        // Return the existing filter instance, if any
        if (this.filter != null)
            return (this.filter);

        // Identify the class loader we will be using
        String filterClass = getFilterClass();
        ClassLoader classLoader = null;
        if (filterClass.startsWith("org.apache.tomcat.servlet."))
            classLoader = this.getClass().getClassLoader();
        else
            classLoader = context.getClassLoader();

        ClassLoader oldCtxClassLoader =
            Thread.currentThread().getContextClassLoader();

        // Instantiate a new instance of this filter and return it
        Class clazz = classLoader.loadClass(filterClass);
        
        
        this.filter = (Filter) clazz.newInstance();
        if (context instanceof ServletContextImpl &&
            ((ServletContextImpl)context).getSwallowOutput()) {
            try {
                SystemLogHandler.startCapture();
                filter.init(this);
            } finally {
                String log = SystemLogHandler.stopCapture();
                if (log != null && log.length() > 0) {
                    getServletContext().log(log);
                }
            }
        } else {
            filter.init(this);
        }
        return (this.filter);

    }


    /**
     * Release the Filter instance associated with this FilterConfig,
     * if there is one.
     */
    void release() {

        if (this.filter != null){
                filter.destroy();
        }
        this.filter = null;

     }


    // -------------------------------------------------------- Private Methods


}
