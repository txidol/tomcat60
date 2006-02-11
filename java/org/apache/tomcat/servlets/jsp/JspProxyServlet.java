package org.apache.tomcat.servlets.jsp;

import java.io.IOException;
import java.util.HashMap;
import java.util.Vector;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** If jasper is found, it'll just forward the calls to jasper jsp servlet.
 *  
 *  If jasper is not found - i.e. runtime without jasper compiler - it'll 
 *  compute the mangled class name ( same code as jasper )
 *  and invoke the jsp servlet directly. If the class is not found, it'll execute
 *   "jspc" and try again. 
 *  
 *  
 * 
 * TODO: test jspc, generate files in or out web-inf dir, modify web.xml
 * 
 * @author Costin Manolache
 */
public class JspProxyServlet extends HttpServlet {
    HttpServlet realJspServlet;
    static HashMap jsps=new HashMap();
    
    protected void service(HttpServletRequest req, HttpServletResponse arg1)
        throws ServletException, IOException 
    {
        if( realJspServlet!=null ) {
            realJspServlet.service(req, arg1);
            return;
        }
        
        String jspUri=null; 
        
        String jspFile = (String)req.getAttribute("org.apache.catalina.jsp_file");
        if (jspFile != null) {
            // JSP is specified via <jsp-file> in <servlet> declaration
            jspUri = jspFile;
        } else {
            // RequestDispatcher.include()
            jspUri = (String)req.getAttribute("javax.servlet.include.servlet_path");
            if (jspUri != null) {
                String pathInfo = (String)req.getAttribute("javax.servlet.include.path_info");
                if (pathInfo != null) {
                    jspUri += pathInfo;
                }
            } else {
                jspUri = req.getServletPath();
                String pathInfo = req.getPathInfo();
                if (pathInfo != null) {
                    jspUri += pathInfo;
                }
            }
        }

        String mangledClass = getClassName( jspFile );
        
        System.err.println("Class: " + mangledClass );
        
        // TODO: if class not found - invoke some external jspc
        HttpServlet jsp = (HttpServlet)jsps.get( mangledClass );
        if( jsp == null ) {
            try {
                Class sC=Class.forName( mangledClass );
                jsp=(HttpServlet)sC.newInstance();
            } catch( Throwable t ) {
            }
        }
        if(jsp == null) {
            
        }
        // try again
        if( jsp == null ) {
            try {
                Class sC=Class.forName( mangledClass );
                jsp=(HttpServlet)sC.newInstance();
            } catch( Throwable t ) {
                t.printStackTrace();
                arg1.setStatus(404);
            }
        }        
        jsp.service( req, arg1);
    }
    
    private void compileJsp(ServletContext ctx, String jspPath) {
        // Params to pass to jspc:
        // classpath 
        
        // webapp base dir
        String baseDir = ctx.getRealPath("/");
        // jsp path ( rel. base dir )
        
        
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

    private String getClassName( String jspUri ) {
        int iSep = jspUri.lastIndexOf('/') + 1;
        String className = makeJavaIdentifier(jspUri.substring(iSep));
        String basePackageName = JSP_PACKAGE_NAME;

        iSep--;
        String derivedPackageName = (iSep > 0) ?
                makeJavaPackage(jspUri.substring(1,iSep)) : "";
        
        if (derivedPackageName.length() == 0) {
            return basePackageName + "." + className;
        }
        return basePackageName + '.' + derivedPackageName + "." + className;
    }

    // ------------- Copied from jasper ---------------------------

    public static final String JSP_PACKAGE_NAME = "org.apache.jsp";

    public static final String makeJavaIdentifier(String identifier) {
        StringBuffer modifiedIdentifier = 
            new StringBuffer(identifier.length());
        if (!Character.isJavaIdentifierStart(identifier.charAt(0))) {
            modifiedIdentifier.append('_');
        }
        for (int i = 0; i < identifier.length(); i++) {
            char ch = identifier.charAt(i);
            if (Character.isJavaIdentifierPart(ch) && ch != '_') {
                modifiedIdentifier.append(ch);
            } else if (ch == '.') {
                modifiedIdentifier.append('_');
            } else {
                modifiedIdentifier.append(mangleChar(ch));
            }
        }
        if (isJavaKeyword(modifiedIdentifier.toString())) {
            modifiedIdentifier.append('_');
        }
        return modifiedIdentifier.toString();
    }

    private static final String javaKeywords[] = {
        "abstract", "assert", "boolean", "break", "byte", "case",
        "catch", "char", "class", "const", "continue",
        "default", "do", "double", "else", "enum", "extends",
        "final", "finally", "float", "for", "goto",
        "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short",
        "static", "strictfp", "super", "switch", "synchronized",
        "this", "throws", "transient", "try", "void",
        "volatile", "while" };

    public static final String makeJavaPackage(String path) {
        String classNameComponents[] = split(path,"/");
        StringBuffer legalClassNames = new StringBuffer();
        for (int i = 0; i < classNameComponents.length; i++) {
            legalClassNames.append(makeJavaIdentifier(classNameComponents[i]));
            if (i < classNameComponents.length - 1) {
                legalClassNames.append('.');
            }
        }
        return legalClassNames.toString();
    }
    private static final String [] split(String path, String pat) {
        Vector comps = new Vector();
        int pos = path.indexOf(pat);
        int start = 0;
        while( pos >= 0 ) {
            if(pos > start ) {
                String comp = path.substring(start,pos);
                comps.add(comp);
            }
            start = pos + pat.length();
            pos = path.indexOf(pat,start);
        }
        if( start < path.length()) {
            comps.add(path.substring(start));
        }
        String [] result = new String[comps.size()];
        for(int i=0; i < comps.size(); i++) {
            result[i] = (String)comps.elementAt(i);
        }
        return result;
    }
            

    /**
     * Test whether the argument is a Java keyword
     */
    public static boolean isJavaKeyword(String key) {
        int i = 0;
        int j = javaKeywords.length;
        while (i < j) {
            int k = (i+j)/2;
            int result = javaKeywords[k].compareTo(key);
            if (result == 0) {
                return true;
            }
            if (result < 0) {
                i = k+1;
            } else {
                j = k;
            }
        }
        return false;
    }

    /**
     * Mangle the specified character to create a legal Java class name.
     */
    public static final String mangleChar(char ch) {
        char[] result = new char[5];
        result[0] = '_';
        result[1] = Character.forDigit((ch >> 12) & 0xf, 16);
        result[2] = Character.forDigit((ch >> 8) & 0xf, 16);
        result[3] = Character.forDigit((ch >> 4) & 0xf, 16);
        result[4] = Character.forDigit(ch & 0xf, 16);
        return new String(result);
    }

} 
