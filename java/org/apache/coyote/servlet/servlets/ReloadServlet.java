package org.apache.coyote.servlet.servlets;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.coyote.servlet.CoyoteServletFacade;

public class ReloadServlet extends HttpServlet {

    protected void doGet(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        
        CoyoteServletFacade servletImpl = CoyoteServletFacade.getServletImpl();
        servletImpl.reloadServletContext(getServletContext());
        
    }

    
}
