/*
 */
package org.apache.tomcat.standalone;

import java.lang.reflect.Method;

/** Process CLI args and dispatch to the right launcher.
 * 
 *  First argument may be:
 *   
 *   -webapps - no server.xml or conf/ is used, you just need a webapps/ dir
 *   -app - no server.xml or webapps/ - you only need individual app dirs, only process 
 *     web.xml. 
 *   -etomcat - deeply embeded, no config file is used. Instead of web.xml it'll 
 *     use programmatic settings. Experimental.
 *   -coyote - just the http adapter - will use Adapters.
 *   [ none of the above ] - old style Bootstrap, using server.xml 
 * 
 * Last 2 modes are for example - the typical use will be to cut&paste the start
 * lines in your app and add the classes to your app jar(s).
 * 
 * @author Costin Manolache
 */
public class Main {

    public static String HELP="";
    
    public static void main(String args[]) {
        if( args.length == 0 ) {
            System.err.println(HELP);
            return;
        }
        String dispatch = args[0];
        String launcher = "org.apache.catalina.startup.Bootstrap";
        if( "-webapps".equals(dispatch) ) {
            launcher = "org.apache.tomcat.standalone.WebappsMain";
        } else if("-app".equals(dispatch)) {
            launcher = "org.apache.tomcat.standalone.SimpleAppsMain";            
        } else if("-etomcat".equals(dispatch)) {
            launcher = "org.apache.tomcat.standalone.ETomcat";            
        } else if("-coyote".equals(dispatch)) {
            launcher = "org.apache.coyote.standalone.Main";            
        }
        try {
            Class launcherClass = Class.forName(launcher);
            Method main = launcherClass.getMethod("main", new Class[] { args.getClass() });
            main.invoke(null, new Object[] {args} );
        } catch( Throwable t ) {
            t.printStackTrace();
        }
    }   
}
