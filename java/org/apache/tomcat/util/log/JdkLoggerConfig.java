/*
 * Copyright 2001-2004 The Apache Software Foundation.
 * Copyright 2004 Costin Manolache
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

package org.apache.tomcat.util.log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.logging.LogManager;

/**
 */
public class JdkLoggerConfig {
        
    public JdkLoggerConfig() {
        InputStream is=getConfig( "logging" );
        if( is!=null ) {
            try {
                LogManager.getLogManager().readConfiguration(is);
            } catch (SecurityException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /** Locate a config stream:
     * 
     *  Uses:
     *   - logging.configuration system property
     *   - ./logging.properties file
     *   - conf/logging.properties
     *   - logging.properties in classpath
     * 
     * @return
     */
    public static InputStream getConfig(String base) {
        //String base="logging"; // "log4j"
        // Initialize:
        // 1. Find config file name 
        String confF=System.getProperty(base + ".configuration");
        if( confF!=null ) {
            // Check if it is a path
        } else {
            confF=base + ".properties";
        }
        
        // URL
        try {
            URL url=new URL( confF );
            InputStream is=url.openStream();
            if( is!=null ) return is;
        } catch( Throwable t ) {
            
        }

        // 2. Try to get the config from a file ( or conf/ )
        File f=new File( confF );
        if( ! f.exists() ) {
            f=new File( "conf/" + confF );
        }
        
        if( f.exists() ) {
            try {
                return new FileInputStream( f );
            } catch (FileNotFoundException e) {
                // ignore
            }
        }
        
        // 3. Load it from CLASSPATH
        InputStream is=JdkLoggerConfig.class.getResourceAsStream( confF );
        
        //No thread class loader 
        if( is!= null ) return is;

        f=new File( System.getProperty("java.home"));
        f=new File( f, "lib/logging.properties");
        if( f.exists() ) {
            try {
                return new FileInputStream(f);
            } catch (FileNotFoundException e) {
                //e.printStackTrace();
            }
        } 
        System.err.println("default logging doesn't exists" + f);
        
        return null;
    }
}
