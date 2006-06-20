/*
 * Copyright 1999,2004-2006 The Apache Software Foundation.
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


package org.apache.tomcat.servlets.file;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;

import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;


/**
 * The default resource-serving servlet for most web applications,
 * used to serve static resources such as HTML pages and images.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 * @version $Revision: 332127 $ $Date: 2005-11-09 20:50:47 +0100 (mer., 09 nov. 2005) $
 */

public class XsltDirServlet extends DefaultServlet {


    /**
     * Allow customized directory listing per directory.
     */
    protected String  localXsltFile = null;


    /**
     * Allow customized directory listing per instance.
     */
    protected String  globalXsltFile = null;


    /**
     *  Decide which way to render. HTML or XML.
     */
    protected InputStream render(String contextPath, File cacheEntry) {
        InputStream xsltInputStream =
            findXsltInputStream(cacheEntry);

        if (xsltInputStream==null) {
            return renderHtml(contextPath, cacheEntry);
        } else {
            return renderXml(contextPath, cacheEntry, xsltInputStream);
        }

    }

    /**
     * Return an InputStream to an HTML representation of the contents
     * of this directory.
     *
     * @param contextPath Context path to which our internal paths are
     *  relative
     */
    protected InputStream renderXml(String contextPath,
                                    File cacheEntry,
                                    InputStream xsltInputStream) {

        StringBuffer sb = new StringBuffer();

        sb.append("<?xml version=\"1.0\"?>");
        sb.append("<listing ");
        sb.append(" contextPath='");
        sb.append(contextPath);
        sb.append("'");
        sb.append(" directory='");
        sb.append(cacheEntry.getName());
        sb.append("' ");
        sb.append(" hasParent='").append(!cacheEntry.getName().equals("/"));
        sb.append("'>");

        sb.append("<entries>");

        String[] files = cacheEntry.list();

        // Render the directory entries within this directory

        // rewriteUrl(contextPath) is expensive. cache result for later reuse
        String rewrittenContextPath =  rewriteUrl(contextPath);

        for (int i=0; i<files.length; i++) {

            String resourceName = files[i];
            String trimmed = resourceName/*.substring(trim)*/;
            if (trimmed.equalsIgnoreCase("WEB-INF") ||
                    trimmed.equalsIgnoreCase("META-INF") ||
                    trimmed.equalsIgnoreCase(localXsltFile))
                continue;

            File childCacheEntry = new File(cacheEntry, files[i]);
            boolean isDir = childCacheEntry.isDirectory();

            sb.append("<entry");
            sb.append(" type='")
            .append(isDir?"dir":"file")
            .append("'");
            sb.append(" urlPath='")
            .append(rewrittenContextPath)
            .append(rewriteUrl(cacheEntry.getName() + resourceName))
            .append(isDir?"/":"")
            .append("'");
            if (isDir) {
                sb.append(" size='");
                displaySize(sb, childCacheEntry.length());
                sb.append("'");
            }
            sb.append(" date='")
            .append(lastModifiedHttp(childCacheEntry))
            .append("'");

            sb.append(">");
            sb.append(trimmed);
            if (isDir)
                sb.append("/");
            sb.append("</entry>");

        }

        sb.append("</entries>");

        String readme = getReadme(cacheEntry);

        if (readme!=null) {
            sb.append("<readme><![CDATA[");
            sb.append(readme);
            sb.append("]]></readme>");
        }


        sb.append("</listing>");


        try {
            TransformerFactory tFactory = TransformerFactory.newInstance();
            Source xmlSource = new StreamSource(new StringReader(sb.toString()));
            Source xslSource = new StreamSource(xsltInputStream);
            Transformer transformer = tFactory.newTransformer(xslSource);

            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            OutputStreamWriter osWriter = new OutputStreamWriter(stream, "UTF8");
            StreamResult out = new StreamResult(osWriter);
            transformer.transform(xmlSource, out);
            osWriter.flush();
            return (new ByteArrayInputStream(stream.toByteArray()));
        } catch (Exception e) {
            log("directory transform failure: " + e.getMessage());
            return renderHtml(contextPath, cacheEntry);
        }
    }


    /**
     * Return the xsl template inputstream (if possible)
     */
    protected InputStream findXsltInputStream(File directory) {

        if (localXsltFile!=null) {
            try {
                File rf = new File(directory, readmeFile);

                if (rf.exists()) {
                    InputStream is = new FileInputStream(rf);
                    if (is!=null)
                        return is;
                }
             } catch(Throwable e) {
                 ; /* Should only be IOException or NamingException
                    * can be ignored
                    */
             }
        }

        /*  Open and read in file in one fell swoop to reduce chance
         *  chance of leaving handle open.
         */
        if (globalXsltFile!=null) {
            FileInputStream fis = null;

            try {
                File f = new File(globalXsltFile);
                if (f.exists()){
                    fis =new FileInputStream(f);
                    byte b[] = new byte[(int)f.length()]; /* danger! */
                    fis.read(b);
                    return new ByteArrayInputStream(b);
                }
            } catch(Throwable e) {
                log("This shouldn't happen (?)...", e);
                return null;
            } finally {
                try {
                    if (fis!=null)
                        fis.close();
                } catch(Throwable e){
                    ;
                }
            }
        }

        return null;

    }
}
