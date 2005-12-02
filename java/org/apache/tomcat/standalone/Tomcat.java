/*
 */
package org.apache.tomcat.standalone;

import java.io.File;
import java.io.IOException;

import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.core.StandardService;
import org.apache.catalina.startup.HostConfig;

public class Tomcat {

    public static void main( String args[] ) {
        try {
            startTomcat();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private static void startTomcat() throws Exception {
        String catalinaHome = System.getProperty("catalina.home");
        if(catalinaHome==null) {
            catalinaHome=System.getProperty("user.dir");
            File home = new File(catalinaHome);
            if (!home.isAbsolute()) {
                try {
                    catalinaHome = home.getCanonicalPath();
                } catch (IOException e) {
                    catalinaHome = home.getAbsolutePath();
                }
            }
            System.setProperty("catalina.home", catalinaHome);
        }
        
        if( System.getProperty("catalina.base") == null ) {
            System.setProperty("catalina.base", catalinaHome);
        }
        System.setProperty("catalina.useNaming", "false");
        
        StandardServer server = new StandardServer();
        server.setPort( -1 );
        //tc.setServer( server );
        
        StandardService service = new StandardService();
        server.addService( service );
        
        Connector connector = new Connector("HTTP/1.1");
        service.addConnector( connector );
        connector.setPort( 8000 );
        
        StandardEngine eng = new StandardEngine();
        eng.setName( "default" );
        eng.setDefaultHost("localhost");
        service.setContainer(eng);
        
        StandardHost host = new StandardHost();
        host.setName( "localhost");
        host.setAppBase("webapps");
        HostConfig hconfig = new HostConfig();
        host.addLifecycleListener( hconfig );
        
        eng.addChild( host );

        server.initialize();
        
        server.start();
    }
}
