/*
 */
package org.apache.tomcat.standalone;

import org.apache.catalina.core.StandardHost;
import org.apache.catalina.startup.HostConfig;

/**
 * Start tomcat using server.xml and web.xml and regular config files 
 * 
 * @author Costin Manolache
 */
public class WebappsMain extends ETomcat {

    public static void main( String args[] ) {
        try {
            WebappsMain etomcat = new WebappsMain();
            
            etomcat.initServer(null);
            etomcat.initConnector(8000);

            // This will load all webapps, context configs, etc 
            etomcat.initAutoconfHost("localhost", "webapps");
            
            etomcat.start();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public StandardHost initAutoconfHost(String hostName, String webappsBase) 
    {
        host = new StandardHost();
        host.setName(hostName);
        
        HostConfig hconfig = new HostConfig();
        host.addLifecycleListener( hconfig );

        host.setAppBase(webappsBase);

        eng.addChild(host);
        return host;
    }

}
