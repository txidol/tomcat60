/*
 */
package org.apache.tomcat.standalone;

import org.apache.catalina.core.StandardContext;
import org.apache.catalina.startup.ContextConfig;

/**
 * Start tomcat using server.xml and web.xml and regular config files 
 * 
 * @author Costin Manolache
 */
public class SingleMain extends ETomcat {

    public static void main( String args[] ) {
        try {
            SingleMain etomcat = new SingleMain();
            
            etomcat.initServer(null);
            etomcat.initConnector(8000);

            // Use this to load indivitdual webapp, without auto-deployment
            etomcat.initHost("localhost");

            if( args.length < 2 ) {
                    etomcat.initWebXmlApp("/", "webapps/ROOT");
            } else {
                etomcat.initWebapp(args[0], args[1]);
                etomcat.initWebappDefaults();
            }

            etomcat.start();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public StandardContext initWebXmlApp(String path, String dir) {
        ctx = new StandardContext();
        ctx.setPath( path );
        ctx.setDocBase(dir);

        // web.xml reader
        ContextConfig ctxCfg = new ContextConfig();
        ctx.addLifecycleListener( ctxCfg );
        
        host.addChild(ctx);
        return ctx;
    }

}
