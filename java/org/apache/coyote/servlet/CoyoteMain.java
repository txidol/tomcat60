package org.apache.coyote.servlet;

import javax.servlet.ServletContext;


/** 
 * Simple example of embeding coyote servlet.
 * 
 */
public class CoyoteMain  {
    CoyoteServletFacade facade;
    
    public CoyoteMain() {        
    }

    /**
     */
    public void run() {
        init();
        start();
    }
    
    public void init() {
        facade = CoyoteServletFacade.getServletImpl();
        facade.setPort(8800);
        facade.getProtocol().getEndpoint().setDaemon(false);        
    }
    
    
    public void start() {
        try {
            ServletContext ctx = facade.addServletContext("localhost", 
                                "webapps/ROOT", "");
            facade.start();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
    
    // ------------------- Main ---------------------
    public static void main( String args[]) {
        CoyoteMain sa=new CoyoteMain();
        sa.run();
    }

    
}