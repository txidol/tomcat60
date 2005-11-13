package org.apache.tomcat.servlets.jsp;

import java.io.IOException;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class JspProxyServlet extends HttpServlet {
    HttpServlet realJspServlet;
    
    protected void service(HttpServletRequest arg0, HttpServletResponse arg1)
        throws ServletException, IOException 
    {
        if( realJspServlet!=null ) {
            realJspServlet.service(arg0, arg1);
            return;
        }
        arg1.setStatus(404);
    }

    public void init(ServletConfig arg0) throws ServletException {
        super.init(arg0);
        try {
            Class jspC = Class.forName("org.apache.jasper.servlet.JspServlet");
            realJspServlet=(HttpServlet)jspC.newInstance();
            realJspServlet.init(arg0);
        } catch (ClassNotFoundException e) {
            // it's ok - no jsp
            log("No JSP servlet");
        } catch (Throwable e ) {
            e.printStackTrace();
            log("No JSP servlet");
        }
    }

    
}
