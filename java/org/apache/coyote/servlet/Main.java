package org.apache.coyote.servlet;

import javax.servlet.ServletContext;


/** 
 * Simple example of embeding coyote servlet.
 * 
 */
public class Main  {
    CoyoteServletFacade facade;
    
    public Main() {        
    }

    /**
     */
    public void run() {
        init();
        start();
    }
    
    public void init() {
        facade = CoyoteServletFacade.getServletImpl();
        facade.initHttp(8800);
        facade.getProtocol().getEndpoint().setDaemon(false);        
    }
    
    
    public void start() {
        try {
            ServletContext ctx = facade.createServletContext("localhost", "");
            facade.setBasePath(ctx, "webapps/ROOT");
            facade.initContext(ctx);
            facade.start();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
    
    // ------------------- Main ---------------------
    public static void main( String args[]) {
        Main sa=new Main();
        sa.run();
    }

    
}